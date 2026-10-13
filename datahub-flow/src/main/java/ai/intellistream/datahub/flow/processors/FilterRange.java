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

import java.util.List;

/**
 * {@code timeseries.filter.range}: splits a series' points by whether they lie in a range. Both
 * sides keep the item's attributes; an empty side is not emitted.
 */
@Component
public class FilterRange implements Processor {

    private static final ProcessorDescriptor DESCRIPTOR = ProcessorDescriptor
            .builder("timeseries.filter.range", "1.0.0")
            .displayName("Filter by range")
            .description("Splits a series' points by whether they lie in a range.")
            .property(PropertyDescriptor.builder("min", PropertyType.NUMBER)
                    .displayName("Min").description("At least one of min, max.")
                    .references().build())
            .property(PropertyDescriptor.builder("max", PropertyType.NUMBER)
                    .displayName("Max").references().build())
            .property(PropertyDescriptor.builder("inclusive", PropertyType.BOOLEAN)
                    .displayName("Inclusive")
                    .description("Whether the bounds themselves are in range.")
                    .defaultValue(true).build())
            .relationship(Relationship.of("inRange", "The points in the range."))
            .relationship(Relationship.of("outOfRange", "The points outside it."))
            .accepts(ContentKind.TIMESERIES)
            .build();

    @Override
    public ProcessorDescriptor describe() {
        return DESCRIPTOR;
    }

    @Override
    public List<String> validate(PropertyValues properties) {
        return Clip.Bounds.validate(properties);
    }

    @Override
    public void onTrigger(ProcessContext context, ProcessSession session) {
        Item item = session.get();
        Clip.Bounds bounds = Clip.Bounds.of(context, item);
        boolean inclusive = context.property("inclusive").asBoolean();
        TimeseriesContent in = item.content(TimeseriesContent.class);
        int n = in.rowCount();
        boolean[] inside = new boolean[n];
        int count = 0;
        for (int i = 0; i < n; i++) {
            double v = in.value(i);
            inside[i] = inclusive ? v >= bounds.min() && v <= bounds.max()
                    : v > bounds.min() && v < bounds.max();
            if (inside[i]) {
                count++;
            }
        }

        if (n == 0) {
            session.remove(item);
        } else if (count == n) {
            session.transfer(item, "inRange");
        } else if (count == 0) {
            session.transfer(item, "outOfRange");
        } else {
            emit(session, item, in, inside, true, count, "inRange");
            emit(session, item, in, inside, false, n - count, "outOfRange");
            session.remove(item);
        }
    }

    private static void emit(ProcessSession session, Item parent, TimeseriesContent in,
                             boolean[] inside, boolean side, int rows, String relationship) {
        TimeseriesContent.Builder out = session.newTimeseries(rows);
        for (int i = 0; i < in.rowCount(); i++) {
            if (inside[i] == side) {
                out.add(in.timestamp(i), in.value(i));
            }
        }
        Item part = session.write(session.create(parent), out.build());
        session.transfer(part, relationship);
    }
}
