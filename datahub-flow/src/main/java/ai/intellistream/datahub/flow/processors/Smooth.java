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
 * {@code timeseries.smooth}: replaces every point with a smoothed value computed over a trailing
 * time window, {@code (t − window, t]}, so a point never depends on later ones. Windows are by time,
 * not by point count, so irregular sampling is handled.
 *
 * <p>Points earlier than one {@code window} after the item's first point have too little history
 * and are passed through unchanged; reading {@code lookback} ≥ {@code window} avoids that for the
 * run's own window.
 */
@Component
public class Smooth implements Processor {

    private static final ProcessorDescriptor DESCRIPTOR = ProcessorDescriptor
            .builder("timeseries.smooth", "1.0.0")
            .displayName("Smooth")
            .description("Replaces every point with a value smoothed over a trailing time window.")
            .property(PropertyDescriptor.builder("method", PropertyType.ENUM)
                    .displayName("Method")
                    .allowableValues(
                            new AllowableValue("median", "Rolling median: removes short spikes, "
                                    + "keeps steps sharp."),
                            new AllowableValue("mean", "Rolling mean."),
                            new AllowableValue("exponential", "Exponential smoothing with time "
                                    + "constant window, weighted by the gap between points."))
                    .defaultValue("median").build())
            .property(PropertyDescriptor.builder("window", PropertyType.DURATION)
                    .displayName("Window").required().build())
            .relationship(Relationship.of("success", "The smoothed series."))
            .accepts(ContentKind.TIMESERIES)
            .build();

    @Override
    public ProcessorDescriptor describe() {
        return DESCRIPTOR;
    }

    @Override
    public List<String> validate(PropertyValues properties) {
        Duration window = Literal.duration(properties.property("window"));
        return window != null && window.toMillis() < 1
                ? List.of("window must be at least a millisecond.") : List.of();
    }

    @Override
    public void onTrigger(ProcessContext context, ProcessSession session) {
        Item item = session.get();
        long window = context.property("window").asDuration().toMillis();
        String method = context.property("method").asString();
        Points in = Points.of(item.content(TimeseriesContent.class));
        double[] smoothed = switch (method) {
            case "exponential" -> exponential(in, window);
            case "mean" -> rolling(in, window, false);
            default -> rolling(in, window, true);
        };
        TimeseriesContent.Builder out = session.newTimeseries(in.n);
        for (int i = 0; i < in.n; i++) {
            boolean enoughHistory = in.t[i] - in.t[0] >= window;
            out.add(in.t[i], enoughHistory ? smoothed[i] : in.v[i]);
        }
        item = session.write(item, out.build());
        session.transfer(item, "success");
    }

    /** The median or mean of each point's window {@code (t − window, t]}. */
    private static double[] rolling(Points in, long window, boolean median) {
        double[] out = new double[in.n];
        SortedWindow sorted = new SortedWindow();
        double sum = 0;
        int first = 0;
        for (int i = 0; i < in.n; i++) {
            sorted.add(in.v[i]);
            sum += in.v[i];
            while (in.t[first] <= in.t[i] - window) {
                sorted.remove(in.v[first]);
                sum -= in.v[first];
                first++;
            }
            out[i] = median ? sorted.median() : sum / sorted.size();
        }
        return out;
    }

    /** {@code s += α (v − s)} with {@code α = 1 − e^(−Δt / window)}, starting from the first value. */
    private static double[] exponential(Points in, long window) {
        double[] out = new double[in.n];
        double s = in.n == 0 ? 0 : in.v[0];
        for (int i = 0; i < in.n; i++) {
            if (i > 0) {
                double alpha = 1 - Math.exp(-(double) (in.t[i] - in.t[i - 1]) / window);
                s += alpha * (in.v[i] - s);
            }
            out[i] = s;
        }
        return out;
    }
}
