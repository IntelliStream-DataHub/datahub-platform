// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.flow.processors;

import ai.intellistream.datahub.api.binary.ArrowIpc.Batch;
import ai.intellistream.datahub.api.binary.ArrowIpc.ColumnView;
import ai.intellistream.datahub.api.binary.DatapointFrame;
import ai.intellistream.datahub.api.binary.DatapointValueType;
import ai.intellistream.datahub.api.responses.DataRetriever;
import ai.intellistream.datahub.flow.api.Item;
import ai.intellistream.datahub.flow.api.ProcessContext;
import ai.intellistream.datahub.flow.api.ProcessException;
import ai.intellistream.datahub.flow.api.ProcessSession;
import ai.intellistream.datahub.flow.api.Processor;
import ai.intellistream.datahub.flow.api.ProcessorDescriptor;
import ai.intellistream.datahub.flow.api.PropertyDescriptor;
import ai.intellistream.datahub.flow.api.PropertyDescriptor.AllowableValue;
import ai.intellistream.datahub.flow.api.PropertyType;
import ai.intellistream.datahub.flow.api.PropertyValues;
import ai.intellistream.datahub.flow.api.Relationship;
import ai.intellistream.datahub.models.IdCollection;
import ai.intellistream.datahub.models.forms.RetrieveFilter;
import ai.intellistream.datahub.sdk.http.DatahubApiException;
import ai.intellistream.datahub.timeseries.Timeseries;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code timeseries.read}: reads series over a window, one {@code timeseries} item per
 * series.
 *
 * <p>Reads through datahub-api's binary read, so a run never parses JSON: frames of Arrow batches in
 * the platform's canonical schema, which the SDK has already decompressed and checked, read here
 * column by column into the item's batch. Numeric value types become doubles; a series that holds
 * text cannot be read by the first version's processors and fails the run.
 *
 * <p>Points are deduplicated by timestamp, keeping the last: the datapoint tables collapse
 * duplicates only when ClickHouse merges, so a point written twice minutes ago can read back twice.
 */
@Component
public class TimeseriesSource implements Processor {

    static final String TYPE = "timeseries.read";

    private static final ProcessorDescriptor DESCRIPTOR = ProcessorDescriptor
            .builder(TYPE, "1.0.0")
            .displayName("Read timeseries")
            .description("Reads series over a window from DataHub: one timeseries item per series.")
            .property(PropertyDescriptor.builder("timeseries", PropertyType.LIST)
                    .displayName("Timeseries")
                    .description("External ids of the series to read.")
                    .lookup("timeseries").required().build())
            .property(PropertyDescriptor.builder("from", PropertyType.INSTANT)
                    .displayName("From")
                    .description("Start of the window, inclusive.")
                    .advanced().references().defaultValue("${run.window.start}").build())
            .property(PropertyDescriptor.builder("to", PropertyType.INSTANT)
                    .displayName("To")
                    .description("End of the window, exclusive.")
                    .advanced().references().defaultValue("${run.window.end}").build())
            .property(PropertyDescriptor.builder("lookback", PropertyType.DURATION)
                    .displayName("Lookback")
                    .description("Also read this much before from, as history for smoothing, spike "
                            + "removal and alarms. Sinks write only from the window's start on.")
                    .defaultValue("PT0S").build())
            .property(PropertyDescriptor.builder("maxPointsPerItem", PropertyType.NUMBER)
                    .displayName("Max points per item")
                    .description("A longer series is split into several items.")
                    .advanced()
                    .defaultValue(100_000).build())
            .property(PropertyDescriptor.builder("onMissing", PropertyType.ENUM)
                    .displayName("When a series is missing")
                    .description("What to do with a series that does not exist or that the run "
                            + "may not read.")
                    .allowableValues(new AllowableValue("fail", "Fail the run."),
                            new AllowableValue("skip", "Skip the series and read the rest."))
                    .defaultValue("fail").advanced().build())
            .relationship(Relationship.of("success", "Each series read, or each part of one."))
            .build();

    @Override
    public ProcessorDescriptor describe() {
        return DESCRIPTOR;
    }

    @Override
    public List<String> validate(PropertyValues properties) {
        List<String> problems = new ArrayList<>();
        List<String> series = properties.property("timeseries").asList();
        if (series != null && series.isEmpty()) {
            problems.add("Name at least one series in timeseries.");
        }
        Double max = Literal.number(properties.property("maxPointsPerItem"));
        if (max != null && max < 1) {
            problems.add("maxPointsPerItem must be at least 1.");
        }
        Duration lookback = Literal.duration(properties.property("lookback"));
        if (lookback != null && lookback.isNegative()) {
            problems.add("lookback must not be negative.");
        }
        return problems;
    }

    @Override
    public void onTrigger(ProcessContext context, ProcessSession session) {
        List<String> series = context.property("timeseries").asList();
        Instant from = required(context.property("from").asInstant(), "from");
        Instant to = required(context.property("to").asInstant(), "to");
        if (!from.isBefore(to)) {
            throw new ProcessException("from (" + from + ") must be before to (" + to + ")");
        }
        Instant readFrom = from.minus(context.property("lookback").asDuration());
        int maxPoints = context.property("maxPointsPerItem").asDouble().intValue();
        boolean skipMissing = "skip".equals(context.property("onMissing").asString());

        Map<String, DatapointValueType> types = types(context, series, skipMissing);
        Map<String, Series> read = read(context, types, readFrom, to);

        for (String externalId : series) {
            if (!types.containsKey(externalId)) {
                continue;
            }
            Series s = read.getOrDefault(externalId, new Series());
            Points points = s.dedupedByTimestamp();
            String uri = TransitUris.timeseries(externalId, readFrom, to);
            int parts = Math.max(1, (points.n + maxPoints - 1) / maxPoints);
            for (int part = 0; part < parts; part++) {
                int start = part * maxPoints;
                int end = Math.min(points.n, start + maxPoints);
                Item item = session.create();
                item = session.putAllAttributes(item, Map.of(Points.EXTERNAL_ID, externalId,
                        Points.WINDOW_START, from.toString(), Points.WINDOW_END, to.toString()));
                item = session.write(item, new Points(Arrays.copyOfRange(points.t, start, end),
                        Arrays.copyOfRange(points.v, start, end), end - start).write(session));
                session.receive(item, uri);
                session.transfer(item, "success");
            }
        }
    }

    /**
     * The value type of every series the run may read, in the order asked. A series that does not
     * exist and one the run may not read look the same: the api answers neither.
     */
    private static Map<String, DatapointValueType> types(ProcessContext context, List<String> series,
                                                         boolean skipMissing) {
        List<IdCollection> ids = series.stream().map(IdCollection::createFromExternalId).toList();
        List<Timeseries> found;
        try {
            found = context.datahub().timeseries().byIds(ids).getItems();
        } catch (DatahubApiException e) {
            throw new ProcessException("Could not look up the series: " + e.getMessage(), e);
        }
        Map<String, DatapointValueType> byExternalId = new HashMap<>();
        for (Timeseries ts : found == null ? List.<Timeseries>of() : found) {
            byExternalId.put(ts.getExternalId(), DatapointValueType.fromName(ts.getValueType()));
        }
        Map<String, DatapointValueType> types = new LinkedHashMap<>();
        List<String> missing = new ArrayList<>();
        for (String externalId : series) {
            DatapointValueType type = byExternalId.get(externalId);
            if (type == null) {
                missing.add(externalId);
            } else if (type == DatapointValueType.TEXT || type == DatapointValueType.MIXED) {
                throw new ProcessException("Series " + externalId + " holds " + type.name()
                        .toLowerCase() + " values; flows read numeric series only");
            } else {
                types.put(externalId, type);
            }
        }
        if (!missing.isEmpty()) {
            String names = String.join(", ", missing);
            if (!skipMissing) {
                throw new ProcessException("These series do not exist, or the run may not read "
                        + "them: " + names);
            }
            context.log().warn("Skipped series that do not exist or that the run may not read: "
                    + names);
        }
        return types;
    }

    /** Every point of every series in {@code [from, to)}, by external id. */
    private static Map<String, Series> read(ProcessContext context,
                                            Map<String, DatapointValueType> types, Instant from,
                                            Instant to) {
        Map<String, Series> out = new HashMap<>();
        if (types.isEmpty()) {
            return out;
        }
        List<RetrieveFilter> items = new ArrayList<>();
        for (String externalId : types.keySet()) {
            RetrieveFilter filter = new RetrieveFilter();
            filter.setExternalId(externalId);
            filter.setStart(from.atZone(ZoneOffset.UTC));
            filter.setEnd(to.atZone(ZoneOffset.UTC));
            items.add(filter);
        }
        DataRetriever<RetrieveFilter> request = new DataRetriever<>();
        request.setItems(items);
        // Missing series were found by the lookup; one deleted since must not fail the read.
        request.setIgnoreUnknownIds(true);

        List<DatapointFrame> frames;
        try {
            frames = context.datahub().timeseries().retrieveBinary(request);
        } catch (DatahubApiException e) {
            throw new ProcessException("Could not read the series: " + e.getMessage(), e);
        }
        for (DatapointFrame frame : frames) {
            if (frame.aggregates()) {
                throw new ProcessException("Frame " + frame.index() + " holds aggregates, not points");
            }
            Map<Long, String> names = new HashMap<>();
            for (int i = 0; i < frame.seriesCount(); i++) {
                names.put(frame.seriesIds()[i], frame.externalIds()[i]);
            }
            DatapointValueType type = frame.valueType();
            for (Batch batch : frame.batches()) {
                ColumnView ids = batch.columns()[0];
                ColumnView timestamps = batch.columns()[1];
                ColumnView values = batch.columns()[2];
                for (int r = 0; r < batch.rows(); r++) {
                    String externalId = names.get(ids.data().getLong(r * 8));
                    out.computeIfAbsent(externalId, k -> new Series())
                            .add(timestamps.data().getLong(r * 8), value(type, values, r));
                }
            }
        }
        return out;
    }

    private static double value(DatapointValueType type, ColumnView values, int row) {
        return switch (type) {
            case FLOAT -> values.data().getDouble(row * 8);
            case FLOAT32 -> values.data().getFloat(row * 4);
            case BIGINT -> values.data().getLong(row * 8);
            // Decimal128 in the canon; every stored value fits the low eight bytes.
            case NUMERIC -> BigDecimal.valueOf(values.data().getLong(row * 16), 6).doubleValue();
            case DECIMAL32 -> BigDecimal.valueOf(values.data().getLong(row * 16), 4).doubleValue();
            case TEXT, MIXED -> throw new ProcessException("Flows read numeric series only, not "
                    + type.name().toLowerCase());
        };
    }

    private static Instant required(Instant value, String property) {
        if (value == null) {
            throw new ProcessException(property + " is not set, and the run has no window to "
                    + "take it from");
        }
        return value;
    }

    /** One series' points as read, in arrival order. */
    private static final class Series {
        private long[] t = new long[64];
        private double[] v = new double[64];
        private int n;

        void add(long timestamp, double value) {
            if (n == t.length) {
                t = Arrays.copyOf(t, n * 2);
                v = Arrays.copyOf(v, n * 2);
            }
            t[n] = timestamp;
            v[n] = value;
            n++;
        }

        /** Sorted by timestamp, the last of several equal timestamps kept. */
        Points dedupedByTimestamp() {
            Points sorted = Points.sortedCopy(t, v, n);
            int kept = 0;
            for (int i = 0; i < sorted.n; i++) {
                if (i + 1 < sorted.n && sorted.t[i + 1] == sorted.t[i]) {
                    continue;
                }
                sorted.t[kept] = sorted.t[i];
                sorted.v[kept] = sorted.v[i];
                kept++;
            }
            return new Points(sorted.t, sorted.v, kept);
        }
    }
}
