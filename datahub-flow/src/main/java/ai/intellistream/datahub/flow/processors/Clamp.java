// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.flow.processors;

import ai.intellistream.datahub.flow.api.ContentKind;
import ai.intellistream.datahub.flow.api.Item;
import ai.intellistream.datahub.flow.api.ProcessContext;
import ai.intellistream.datahub.flow.api.ProcessSession;
import ai.intellistream.datahub.flow.api.Processor;
import ai.intellistream.datahub.flow.api.ProcessorDescriptor;
import ai.intellistream.datahub.flow.api.PropertyDescriptor;
import ai.intellistream.datahub.flow.api.PropertyType;
import ai.intellistream.datahub.flow.api.PropertyValues;
import ai.intellistream.datahub.flow.api.Relationship;
import ai.intellistream.datahub.flow.api.TimeseriesContent;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code clamp}: limits values to a range — a value below {@code min} becomes {@code min}, one above
 * {@code max} becomes {@code max}. Every point is kept. Sets {@code clamp.count}, the number of
 * points it changed.
 */
@Component
public class Clamp implements Processor {

    private static final ProcessorDescriptor DESCRIPTOR = ProcessorDescriptor
            .builder("clamp", "1.0.0")
            .displayName("Clamp")
            .category(ProcessorDescriptor.Category.TRANSFORM)
            .description("Limits values to a range; every point is kept.")
            .property(PropertyDescriptor.builder("min", PropertyType.NUMBER)
                    .displayName("Min")
                    .description("Values below this are raised to it. Set min, max or both.")
                    .references().build())
            .property(PropertyDescriptor.builder("max", PropertyType.NUMBER)
                    .displayName("Max")
                    .description("Values above this are lowered to it. Set min, max or both.")
                    .references().build())
            .relationship(Relationship.of("success", "The clamped series; clamp.count is set."))
            .accepts(ContentKind.TIMESERIES)
            .build();

    @Override
    public ProcessorDescriptor describe() {
        return DESCRIPTOR;
    }

    @Override
    public List<String> validate(PropertyValues properties) {
        return Bounds.validate(properties);
    }

    @Override
    public void onTrigger(ProcessContext context, ProcessSession session) {
        Item item = session.get();
        Bounds bounds = Bounds.of(context, item);
        TimeseriesContent in = item.content(TimeseriesContent.class);
        TimeseriesContent.Builder out = session.newTimeseries(in.rowCount());
        int changed = 0;
        for (int i = 0; i < in.rowCount(); i++) {
            double v = in.value(i);
            double clamped = Math.min(Math.max(v, bounds.min()), bounds.max());
            if (clamped != v) {
                changed++;
            }
            out.add(in.timestamp(i), clamped);
        }
        item = session.write(item, out.build());
        item = session.putAttribute(item, "clamp.count", Integer.toString(changed));
        session.transfer(item, "success");
    }

    /**
     * The {@code min} and {@code max} a processor is configured with, either of which may be unset
     * (and is then unbounded).
     */
    record Bounds(double min, double max) {

        static List<String> validate(PropertyValues properties) {
            List<String> problems = new ArrayList<>();
            if (!properties.property("min").isSet() && !properties.property("max").isSet()) {
                problems.add("Set min, max or both.");
            }
            Double min = Literal.number(properties.property("min"));
            Double max = Literal.number(properties.property("max"));
            if (min != null && max != null && min > max) {
                problems.add("min must not be greater than max.");
            }
            return problems;
        }

        static Bounds of(ProcessContext context, Item item) {
            Double min = context.property("min").evaluate(item).asDouble();
            Double max = context.property("max").evaluate(item).asDouble();
            return new Bounds(min == null ? Double.NEGATIVE_INFINITY : min,
                    max == null ? Double.POSITIVE_INFINITY : max);
        }
    }
}
