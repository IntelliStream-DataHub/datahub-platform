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
import java.util.List;

/**
 * {@code timeseries.resample}: puts a series on a regular interval. The points in each bucket of
 * length {@code interval} become one point, timestamped at the bucket's start. Buckets are aligned
 * to whole intervals since the epoch (a {@code PT1H} bucket starts on the hour), so the same input
 * gives the same buckets in every run. A bucket with no points produces no point.
 */
@Component
public class Resample implements Processor {

    private static final ProcessorDescriptor DESCRIPTOR = ProcessorDescriptor
            .builder("timeseries.resample", "1.0.0")
            .displayName("Resample")
            .description("Puts a series on a regular interval, one point per bucket.")
            .property(PropertyDescriptor.builder("interval", PropertyType.DURATION)
                    .displayName("Interval").required().build())
            .property(PropertyDescriptor.builder("aggregate", PropertyType.ENUM)
                    .displayName("Aggregate")
                    .description("How a bucket's points become one.")
                    .allowableValues(new AllowableValue("mean", "The mean."),
                            new AllowableValue("min", "The smallest value."),
                            new AllowableValue("max", "The largest value."),
                            new AllowableValue("first", "The earliest point's value."),
                            new AllowableValue("last", "The latest point's value."),
                            new AllowableValue("sum", "The sum."),
                            new AllowableValue("count", "How many points."))
                    .defaultValue("mean").build())
            .relationship(Relationship.of("success", "The resampled series."))
            .accepts(ContentKind.TIMESERIES)
            .build();

    @Override
    public ProcessorDescriptor describe() {
        return DESCRIPTOR;
    }

    @Override
    public List<String> validate(PropertyValues properties) {
        Duration interval = Literal.duration(properties.property("interval"));
        return interval != null && (interval.isNegative() || interval.toMillis() < 1)
                ? List.of("interval must be at least a millisecond.") : List.of();
    }

    @Override
    public void onTrigger(ProcessContext context, ProcessSession session) {
        Item item = session.get();
        long interval = context.property("interval").asDuration().toMillis();
        String aggregate = context.property("aggregate").asString();
        Points in = Points.of(item.content(TimeseriesContent.class));

        TimeseriesContent.Builder out = session.newTimeseries(Math.min(in.n, 1024));
        int i = 0;
        while (i < in.n) {
            long bucket = Math.floorDiv(in.t[i], interval) * interval;
            int j = i;
            while (j < in.n && in.t[j] < bucket + interval) {
                j++;
            }
            out.add(bucket, aggregate(aggregate, in.v, i, j));
            i = j;
        }
        item = session.write(item, out.build());
        session.transfer(item, "success");
    }

    /** {@code values[from, to)} combined; never empty. */
    private static double aggregate(String aggregate, double[] values, int from, int to) {
        return switch (aggregate) {
            case "min" -> {
                double min = values[from];
                for (int k = from + 1; k < to; k++) {
                    min = Math.min(min, values[k]);
                }
                yield min;
            }
            case "max" -> {
                double max = values[from];
                for (int k = from + 1; k < to; k++) {
                    max = Math.max(max, values[k]);
                }
                yield max;
            }
            case "first" -> values[from];
            case "last" -> values[to - 1];
            case "count" -> to - from;
            case "sum", "mean" -> {
                double sum = 0;
                for (int k = from; k < to; k++) {
                    sum += values[k];
                }
                yield aggregate.equals("sum") ? sum : sum / (to - from);
            }
            default -> throw new IllegalStateException("Unknown aggregate " + aggregate);
        };
    }
}
