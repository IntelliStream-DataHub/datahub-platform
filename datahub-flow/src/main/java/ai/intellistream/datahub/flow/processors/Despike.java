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
 * {@code timeseries.despike}: finds spikes and removes or replaces them, leaving every other point
 * untouched — a Hampel filter. A point is a spike when it is more than {@code threshold} × the
 * median absolute deviation away from the median of the {@code window} before it,
 * {@code [t − window, t)}, taken over the original values.
 *
 * <p>A point is judged only with enough history: at least one {@code window} after the item's first
 * point, and at least three points in its window. Earlier points are kept as they are; a
 * {@code lookback} of at least {@code window} gives the run's own window that history.
 */
@Component
public class Despike implements Processor {

    /** Fewer points than this before a point say too little about what is normal there. */
    private static final int MIN_HISTORY = 3;

    private static final ProcessorDescriptor DESCRIPTOR = ProcessorDescriptor
            .builder("timeseries.despike", "1.0.0")
            .displayName("Remove spikes")
            .description("Finds spikes (a Hampel filter) and removes or replaces them.")
            .property(PropertyDescriptor.builder("window", PropertyType.DURATION)
                    .displayName("Window").required().build())
            .property(PropertyDescriptor.builder("threshold", PropertyType.NUMBER)
                    .displayName("Threshold").description("In median absolute deviations.")
                    .defaultValue(3).build())
            .property(PropertyDescriptor.builder("action", PropertyType.ENUM)
                    .displayName("Action")
                    .allowableValues(new AllowableValue("remove", "Remove the spike."),
                            new AllowableValue("replace", "Replace it with the rolling median."))
                    .defaultValue("remove").build())
            .relationship(Relationship.of("success", "The series without spikes; despike.count "
                    + "is set."))
            .relationship(new Relationship("spikes", "The spikes found, as a timeseries item, for "
                    + "alarming or inspection. Emitted only when there are any.",
                    Relationship.Unconnected.TERMINATE))
            .accepts(ContentKind.TIMESERIES)
            .build();

    @Override
    public ProcessorDescriptor describe() {
        return DESCRIPTOR;
    }

    @Override
    public List<String> validate(PropertyValues properties) {
        List<String> problems = new ArrayList<>();
        Duration window = Literal.duration(properties.property("window"));
        if (window != null && window.toMillis() < 1) {
            problems.add("window must be at least a millisecond.");
        }
        Double threshold = Literal.number(properties.property("threshold"));
        if (threshold != null && threshold <= 0) {
            problems.add("threshold must be greater than 0.");
        }
        return problems;
    }

    @Override
    public void onTrigger(ProcessContext context, ProcessSession session) {
        Item item = session.get();
        long window = context.property("window").asDuration().toMillis();
        double threshold = context.property("threshold").asDouble();
        boolean replace = "replace".equals(context.property("action").asString());
        Points in = Points.of(item.content(TimeseriesContent.class));

        boolean[] spike = new boolean[in.n];
        double[] medians = new double[in.n];
        int spikes = 0;
        SortedWindow before = new SortedWindow();
        int first = 0;
        for (int i = 0; i < in.n; i++) {
            while (first < i && in.t[first] < in.t[i] - window) {
                before.remove(in.v[first]);
                first++;
            }
            if (in.t[i] - in.t[0] >= window && before.size() >= MIN_HISTORY) {
                double median = before.median();
                double mad = before.medianAbsoluteDeviation(median);
                if (Math.abs(in.v[i] - median) > threshold * mad) {
                    spike[i] = true;
                    medians[i] = median;
                    spikes++;
                }
            }
            before.add(in.v[i]);
        }

        if (spikes > 0) {
            TimeseriesContent.Builder found = session.newTimeseries(spikes);
            TimeseriesContent.Builder kept = session.newTimeseries(replace ? in.n : in.n - spikes);
            for (int i = 0; i < in.n; i++) {
                if (spike[i]) {
                    found.add(in.t[i], in.v[i]);
                    if (replace) {
                        kept.add(in.t[i], medians[i]);
                    }
                } else {
                    kept.add(in.t[i], in.v[i]);
                }
            }
            Item spikesItem = session.write(session.create(item), found.build());
            session.transfer(spikesItem, "spikes");
            item = session.write(item, kept.build());
        }
        item = session.putAttribute(item, "despike.count", Integer.toString(spikes));
        session.transfer(item, "success");
    }
}
