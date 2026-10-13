// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.flow.processors;

import ai.intellistream.datahub.flow.api.ContentKind;
import ai.intellistream.datahub.flow.api.Item;
import ai.intellistream.datahub.flow.api.ProcessContext;
import ai.intellistream.datahub.flow.api.ProcessSession;
import ai.intellistream.datahub.flow.api.Processor;
import ai.intellistream.datahub.flow.api.ProcessorDescriptor;
import ai.intellistream.datahub.flow.api.PropertyDescriptor;
import ai.intellistream.datahub.flow.api.PropertyDescriptor.AllowableValue;
import ai.intellistream.datahub.flow.api.PropertyType;
import ai.intellistream.datahub.flow.api.PropertyValues;
import ai.intellistream.datahub.flow.api.Relationship;
import ai.intellistream.datahub.flow.api.TimeseriesContent;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code interpolate}: fills gaps between known points with points every
 * {@code interval}. Only gaps between two known points are filled — never before the first or after
 * the last, so nothing is extrapolated (a {@code lookback} lets a gap at the start of the window be
 * filled).
 *
 * <p>Points are added every {@code interval} after the earlier point, as long as the later point is
 * more than half an interval further on. So a gap is filled once it is longer than one and a half
 * intervals, and ordinary jitter in a series' sampling — a point a second late on a one-minute
 * series — adds nothing. Sets {@code interpolate.count}, the number of points added.
 */
@Component
public class Interpolate implements Processor {

    private static final ProcessorDescriptor DESCRIPTOR = ProcessorDescriptor
            .builder("interpolate", "1.0.0")
            .displayName("Fill gaps")
            .category(ProcessorDescriptor.Category.CLEAN)
            .description("Fills gaps between known points; never extrapolates.")
            .property(PropertyDescriptor.builder("interval", PropertyType.DURATION)
                    .displayName("Interval").description("The series' expected sampling interval.")
                    .required().defaultValue("PT1M").build())
            .property(PropertyDescriptor.builder("method", PropertyType.ENUM)
                    .displayName("Method")
                    .allowableValues(new AllowableValue("linear", "Linear between the two neighbours."),
                            new AllowableValue("previous", "Hold the last value."))
                    .defaultValue("linear").build())
            .property(PropertyDescriptor.builder("maxGap", PropertyType.DURATION)
                    .displayName("Max gap")
                    .description("Leave gaps longer than this unfilled; unset fills every gap.")
                    .advanced().build())
            .relationship(Relationship.of("success", "The filled series; interpolate.count is set."))
            .accepts(ContentKind.TIMESERIES)
            .build();

    @Override
    public ProcessorDescriptor describe() {
        return DESCRIPTOR;
    }

    @Override
    public List<String> validate(PropertyValues properties) {
        List<String> problems = new ArrayList<>();
        Duration interval = Literal.duration(properties.property("interval"));
        if (interval != null && interval.toMillis() < 1) {
            problems.add("interval must be at least a millisecond.");
        }
        Duration maxGap = Literal.duration(properties.property("maxGap"));
        if (interval != null && maxGap != null && maxGap.compareTo(interval) <= 0) {
            problems.add("maxGap must be longer than interval.");
        }
        return problems;
    }

    @Override
    public void onTrigger(ProcessContext context, ProcessSession session) {
        Item item = session.get();
        long interval = context.property("interval").asDuration().toMillis();
        Duration maxGapSetting = context.property("maxGap").asDuration();
        long maxGap = maxGapSetting == null ? Long.MAX_VALUE : maxGapSetting.toMillis();
        boolean linear = !"previous".equals(context.property("method").asString());
        Points in = Points.of(item.content(TimeseriesContent.class));

        TimeseriesContent.Builder out = session.newTimeseries(in.n);
        int added = 0;
        for (int i = 0; i < in.n; i++) {
            out.add(in.t[i], in.v[i]);
            if (i + 1 == in.n) {
                break;
            }
            long from = in.t[i];
            long to = in.t[i + 1];
            if (to - from > maxGap) {
                continue;
            }
            for (long t = from + interval; to - t > interval / 2; t += interval) {
                double v = linear
                        ? in.v[i] + (in.v[i + 1] - in.v[i]) * (t - from) / (double) (to - from)
                        : in.v[i];
                out.add(t, v);
                added++;
            }
        }
        item = session.write(item, out.build());
        item = session.putAttribute(item, "interpolate.count", Integer.toString(added));
        session.transfer(item, "success");
    }
}
