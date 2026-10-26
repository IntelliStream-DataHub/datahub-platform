// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.flow.processors;

import ai.intellistream.datahub.flow.api.ContentKind;
import ai.intellistream.datahub.flow.api.EventsContent;
import ai.intellistream.datahub.flow.api.EventsContent.EventRow;
import ai.intellistream.datahub.flow.api.Item;
import ai.intellistream.datahub.flow.api.ProcessContext;
import ai.intellistream.datahub.flow.api.ProcessSession;
import ai.intellistream.datahub.flow.api.Processor;
import ai.intellistream.datahub.flow.api.ProcessorDescriptor;
import ai.intellistream.datahub.flow.api.PropertyDescriptor;
import ai.intellistream.datahub.flow.api.PropertyDescriptor.AllowableValue;
import ai.intellistream.datahub.flow.api.PropertyType;
import ai.intellistream.datahub.flow.api.Relationship;
import ai.intellistream.datahub.flow.api.TimeWindow;
import ai.intellistream.datahub.flow.api.TimeseriesContent;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * {@code threshold}: finds the periods a series is above (or below) a threshold and
 * produces them as an {@code events} item in lifecycle form — an {@code active} row when a period
 * starts and a {@code cleared} row when it ends, sharing an external id built from the series and
 * the period's start. The peak (or trough) and the threshold go in the rows' metadata.
 *
 * <p>A period starts at the first point beyond the threshold and ends at the first point back
 * within it. A period that straddles two windows is seen by two runs, and the rows are emitted so
 * that the lifecycle joins up: a row is emitted only by the run whose window holds its time. So the
 * first run emits {@code active}; the second, whose {@code lookback} reaches back to the period's
 * start, finds the same start and so the same external id, and emits only {@code cleared}. Without
 * that lookback the second run sees a period starting at its window and opens a second lifecycle.
 *
 * <p>{@code minDuration} is judged on periods that have ended. One still going at the end of the
 * data is emitted as {@code active} whatever its length so far, since the run cannot know how long
 * it will last; its {@code cleared} row is then emitted by whichever run sees it end, even if it
 * turned out short.
 */
@Component
public class Threshold implements Processor {

    private static final ProcessorDescriptor DESCRIPTOR = ProcessorDescriptor
            .builder("threshold", "1.0.0")
            .displayName("Threshold periods")
            .category(ProcessorDescriptor.Category.DETECT)
            .description("Finds the periods a series is above or below a threshold, as event "
                    + "lifecycles.")
            .property(PropertyDescriptor.builder("threshold", PropertyType.NUMBER)
                    .displayName("Threshold").required().references().build())
            .property(PropertyDescriptor.builder("direction", PropertyType.ENUM)
                    .displayName("Direction")
                    .allowableValues(new AllowableValue("above", "Above", "Periods above the threshold."),
                            new AllowableValue("below", "Below", "Periods below the threshold."))
                    .defaultValue("above").build())
            .property(PropertyDescriptor.builder("minDuration", PropertyType.DURATION)
                    .displayName("Min duration").description("Ignore periods shorter than this.")
                    .defaultValue("PT0S").build())
            .relationship(new Relationship("alarms", "The periods found, as an events item; "
                    + "emitted only when there is at least one row. Must be connected, usually "
                    + "to Write events.", Relationship.Unconnected.MUST_CONNECT))
            .relationship(Relationship.of("success", "The input series, unchanged."))
            .accepts(ContentKind.TIMESERIES)
            .build();

    @Override
    public ProcessorDescriptor describe() {
        return DESCRIPTOR;
    }

    @Override
    public void onTrigger(ProcessContext context, ProcessSession session) {
        Item item = session.get();
        double threshold = context.property("threshold").evaluate(item).asDouble();
        boolean above = !"below".equals(context.property("direction").asString());
        long minDuration = context.property("minDuration").asDuration().toMillis();
        Points in = Points.of(item.content(TimeseriesContent.class));
        TimeWindow window = Points.window(item);
        long windowStart = window == null ? Long.MIN_VALUE : window.start().toEpochMilli();
        long windowEnd = window == null ? Long.MAX_VALUE : window.end().toEpochMilli();
        String series = item.attribute(Points.EXTERNAL_ID) != null
                ? item.attribute(Points.EXTERNAL_ID) : item.id().toString();
        String extreme = above ? "peak" : "trough";

        List<EventRow> rows = new ArrayList<>();
        int i = 0;
        while (i < in.n) {
            if (!beyond(in.v[i], threshold, above)) {
                i++;
                continue;
            }
            long start = in.t[i];
            double peak = in.v[i];
            int j = i;
            while (j < in.n && beyond(in.v[j], threshold, above)) {
                peak = above ? Math.max(peak, in.v[j]) : Math.min(peak, in.v[j]);
                j++;
            }
            boolean ended = j < in.n;
            long end = ended ? in.t[j] : in.t[j - 1];
            boolean longEnough = end - start >= minDuration;
            String externalId = series + ":" + (above ? "above" : "below") + ":"
                    + Instant.ofEpochMilli(start);
            Map<String, String> metadata = Map.of("threshold", Double.toString(threshold),
                    extreme, Double.toString(peak));

            if (inWindow(start, windowStart, windowEnd) && (!ended || longEnough)) {
                rows.add(new EventRow(externalId, Instant.ofEpochMilli(start), "active", metadata));
            }
            if (ended && inWindow(end, windowStart, windowEnd)
                    && (longEnough || start < windowStart)) {
                rows.add(new EventRow(externalId, Instant.ofEpochMilli(end), "cleared",
                        Map.of("threshold", Double.toString(threshold), extreme,
                                Double.toString(peak), "duration",
                                Duration.ofMillis(end - start).toString())));
            }
            i = j;
        }

        if (!rows.isEmpty()) {
            Item alarms = session.write(session.create(item), EventsContent.of(rows));
            session.transfer(alarms, "alarms");
        }
        session.transfer(item, "success");
    }

    private static boolean beyond(double value, double threshold, boolean above) {
        return above ? value > threshold : value < threshold;
    }

    private static boolean inWindow(long t, long start, long end) {
        return t >= start && t < end;
    }
}
