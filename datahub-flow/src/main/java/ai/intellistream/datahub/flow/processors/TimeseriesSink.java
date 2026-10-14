// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.flow.processors;

import ai.intellistream.datahub.api.binary.DatapointFrameWriter;
import ai.intellistream.datahub.api.binary.DatapointValueType;
import ai.intellistream.datahub.api.binary.FrameLimits;
import ai.intellistream.datahub.api.binary.ZstdPayloadCodec;
import ai.intellistream.datahub.flow.api.Item;
import ai.intellistream.datahub.flow.api.ProcessContext;
import ai.intellistream.datahub.flow.api.ProcessException;
import ai.intellistream.datahub.flow.api.ProcessSession;
import ai.intellistream.datahub.flow.api.Processor;
import ai.intellistream.datahub.flow.api.ProcessorDescriptor;
import ai.intellistream.datahub.flow.api.PropertyDescriptor;
import ai.intellistream.datahub.flow.api.PropertyType;
import ai.intellistream.datahub.flow.api.PropertyValues;
import ai.intellistream.datahub.flow.api.Relationship;
import ai.intellistream.datahub.flow.api.TimeWindow;
import ai.intellistream.datahub.flow.api.TimeseriesContent;
import ai.intellistream.datahub.models.IdCollection;
import ai.intellistream.datahub.sdk.http.DatahubApiException;
import ai.intellistream.datahub.timeseries.Timeseries;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * {@code write-timeseries}: writes a {@code timeseries} item's datapoints to a series, and
 * passes the item on unchanged.
 *
 * <p>Writes only the points in the item's window ({@code [window.start, window.end)}); history a
 * source read through {@code lookback} is not written. It builds the platform's binary datapoint
 * frames from the item's columns, at the target's own value type, and sends them in one request
 * per 32 frames: the api refuses a frame whose series does not exist before publishing anything,
 * so a missing target fails that item and nothing else.
 *
 * <p>A run never creates a series. With {@code create}, the targets are created when the flow is
 * saved, by the person saving it; at run time a target that is missing fails the item like any
 * other.
 */
@Component
public class TimeseriesSink implements Processor {

    static final String TYPE = "write-timeseries";

    private static final ZstdPayloadCodec CODEC = new ZstdPayloadCodec(3);

    private static final ProcessorDescriptor DESCRIPTOR = ProcessorDescriptor
            .builder(TYPE, "1.0.0")
            .displayName("Write timeseries")
            .category(ProcessorDescriptor.Category.READ_WRITE)
            .description("Writes a timeseries item's points in its window to a series.")
            .property(PropertyDescriptor.builder("target", PropertyType.STRING)
                    .displayName("Target")
                    .description("External id of the series to write to. The default is a "
                            + "series of its own beside each one read, never the one read: that "
                            + "would overwrite the raw data in place.")
                    .lookup("timeseries").required().references()
                    .defaultValue("${timeseries.externalId}_out").build())
            .property(PropertyDescriptor.builder("create", PropertyType.BOOLEAN)
                    .displayName("Create")
                    .description("true: targets that do not exist are created when the flow is "
                            + "saved. false: every target must already exist.")
                    .defaultValue(true).build())
            .property(PropertyDescriptor.builder("name", PropertyType.STRING)
                    .displayName("Name").description("Name of a created series; its external "
                            + "id when unset.")
                    .advanced().references().build())
            .property(PropertyDescriptor.builder("unit", PropertyType.STRING)
                    .displayName("Unit").description("Unit of a created series; when unset, "
                            + "that of the series read.")
                    .advanced().references().build())
            .property(PropertyDescriptor.builder("description", PropertyType.STRING)
                    .displayName("Description").description("Description of a created series.")
                    .advanced().references().build())
            .property(PropertyDescriptor.builder("dataSet", PropertyType.STRING)
                    .displayName("Dataset")
                    .description("External id of the dataset of created series: any dataset the "
                            + "person saving the flow may write. The flow's own when unset.")
                    .lookup("dataset").advanced().build())
            .relationship(new Relationship("success", "Written; the item passes on unchanged.",
                    Relationship.Unconnected.TERMINATE))
            .relationship(new Relationship("failure", "Not written; failure.reason says why. "
                    + "Left unconnected, a failed write fails the run.",
                    Relationship.Unconnected.FAIL_RUN))
            .build();

    @Override
    public ProcessorDescriptor describe() {
        return DESCRIPTOR;
    }

    @Override
    public List<String> validate(PropertyValues properties) {
        List<String> problems = new ArrayList<>();
        if (!Boolean.TRUE.equals(properties.property("create").asBoolean())) {
            for (String p : List.of("name", "unit", "description", "dataSet")) {
                if (properties.property(p).isSet()) {
                    problems.add(p + " is only used with create.");
                }
            }
        }
        return problems;
    }

    @Override
    public void onTrigger(ProcessContext context, ProcessSession session) {
        Item item = session.get();
        String target = context.property("target").evaluate(item).asString();
        Points points = inWindow(item);

        if (context.isPreview()) {
            context.log().info("Would write " + points.n + " points to " + target);
            item = session.putAllAttributes(item, Map.of("preview.target", target,
                    "preview.points", Integer.toString(points.n)));
            session.transfer(item, "success");
            return;
        }
        if (points.n == 0) {
            session.transfer(item, "success");
            return;
        }

        Timeseries series = lookUp(context, target);
        if (series == null) {
            fail(session, item, "Series " + target + " does not exist, or the run may not write it");
            return;
        }
        DatapointValueType type = DatapointValueType.fromName(series.getValueType());
        if (type == DatapointValueType.TEXT) {
            fail(session, item, "Series " + target + " holds text; a timeseries item is numeric");
            return;
        }

        List<Frame> frames;
        try {
            frames = frames(type, series.getId(), target, points);
        } catch (IllegalArgumentException e) {
            fail(session, item, "A value does not fit series " + target + " (" + type.name()
                    .toLowerCase() + "): " + e.getMessage());
            return;
        }
        try {
            for (List<byte[]> request : requests(frames)) {
                context.datahub().timeseries().ingestFrames(request);
            }
        } catch (DatahubApiException e) {
            if (!refused(e)) {
                throw new ProcessException("Writing to " + target + " failed: " + e.getMessage(), e);
            }
            fail(session, item, "DataHub refused the write to " + target + " (" + e.statusCode()
                    + "): " + detail(e));
            return;
        }
        session.send(item, TransitUris.timeseries(target, Instant.ofEpochMilli(points.t[0]),
                Instant.ofEpochMilli(points.t[points.n - 1] + 1)));
        session.transfer(item, "success");
    }

    /** The item's points inside its window, or all of them when it has none. */
    private static Points inWindow(Item item) {
        Points all = Points.of(item.content(TimeseriesContent.class));
        TimeWindow window = Points.window(item);
        if (window == null) {
            return all;
        }
        long from = window.start().toEpochMilli();
        long to = window.end().toEpochMilli();
        long[] t = new long[all.n];
        double[] v = new double[all.n];
        int n = 0;
        for (int i = 0; i < all.n; i++) {
            if (all.t[i] >= from && all.t[i] < to) {
                t[n] = all.t[i];
                v[n] = all.v[i];
                n++;
            }
        }
        return new Points(t, v, n);
    }

    private static Timeseries lookUp(ProcessContext context, String target) {
        List<Timeseries> found;
        try {
            found = context.datahub().timeseries()
                    .byIds(List.of(IdCollection.createFromExternalId(target))).getItems();
        } catch (DatahubApiException e) {
            throw new ProcessException("Could not look up series " + target + ": " + e.getMessage(), e);
        }
        if (found == null) {
            return null;
        }
        return found.stream().filter(t -> target.equals(t.getExternalId())).findFirst().orElse(null);
    }

    /** A built frame and the uncompressed size the api counts against a request. */
    private record Frame(byte[] bytes, long rawBytes) {
    }

    /** The points as frames of at most the type's row cap each. */
    private static List<Frame> frames(DatapointValueType type, long id, String externalId,
                                      Points points) {
        List<Frame> frames = new ArrayList<>();
        int cap = FrameLimits.maxRows(type);
        for (int start = 0; start < points.n; start += cap) {
            DatapointFrameWriter writer = DatapointFrameWriter.forType(type).series(id, externalId);
            for (int i = start; i < Math.min(points.n, start + cap); i++) {
                add(writer, type, id, points.t[i], points.v[i]);
            }
            frames.add(new Frame(writer.build(CODEC), writer.estimatedRawBytes()));
        }
        return frames;
    }

    private static void add(DatapointFrameWriter writer, DatapointValueType type, long id,
                            long timestamp, double value) {
        switch (type) {
            case FLOAT -> writer.addFloat(id, timestamp, value);
            case FLOAT32 -> writer.addFloat32(id, timestamp, (float) value);
            case BIGINT -> {
                if (!Double.isFinite(value)) {
                    throw new IllegalArgumentException(value + " is not an integer");
                }
                writer.addBigint(id, timestamp, Math.round(value));
            }
            case NUMERIC -> writer.addNumeric(id, timestamp, decimal(value));
            case DECIMAL32 -> writer.addDecimal32(id, timestamp, decimal(value));
            case MIXED -> writer.addMixed(id, timestamp, Double.toString(value));
            case TEXT -> throw new IllegalArgumentException("text series");
        }
    }

    private static BigDecimal decimal(double value) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(value + " is not a decimal number");
        }
        return BigDecimal.valueOf(value);
    }

    /** Frames grouped into requests within the api's caps on frames and raw bytes per request. */
    private static List<List<byte[]>> requests(List<Frame> frames) {
        List<List<byte[]>> requests = new ArrayList<>();
        List<byte[]> current = new ArrayList<>();
        long raw = 0;
        for (Frame frame : frames) {
            if (!current.isEmpty() && (current.size() == FrameLimits.MAX_FRAMES_PER_REQUEST
                    || raw + frame.rawBytes() > FrameLimits.MAX_REQUEST_RAW_BYTES)) {
                requests.add(current);
                current = new ArrayList<>();
                raw = 0;
            }
            current.add(frame.bytes());
            raw += frame.rawBytes();
        }
        if (!current.isEmpty()) {
            requests.add(current);
        }
        return requests;
    }

    /**
     * A refusal that sending again will not change — a missing series, a value the series cannot
     * hold, no access — goes to {@code failure}. A timeout, an overload or a server error is thrown,
     * so the processor's {@code retry} applies.
     */
    static boolean refused(DatahubApiException e) {
        int status = e.statusCode();
        return status >= 400 && status < 500 && status != 408 && status != 429;
    }

    static String detail(DatahubApiException e) {
        String detail = e.problem() == null ? null : e.problem().detail();
        return detail != null ? detail : e.getMessage();
    }

    private static void fail(ProcessSession session, Item item, String reason) {
        item = session.putAttribute(item, "failure.reason", reason);
        session.transfer(item, "failure");
    }
}
