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
import ai.intellistream.datahub.flow.api.Relationship;
import ai.intellistream.datahub.flow.api.TimeseriesContent;
import org.springframework.stereotype.Component;

/**
 * {@code scale}: {@code value × factor + offset} for every point. Covers unit
 * conversion, adding or subtracting a constant, multiplying and dividing.
 */
@Component
public class Scale implements Processor {

    private static final ProcessorDescriptor DESCRIPTOR = ProcessorDescriptor
            .builder("scale", "1.0.0")
            .displayName("Scale")
            .category(ProcessorDescriptor.Category.TRANSFORM)
            .description("value × factor + offset for every point.")
            .property(PropertyDescriptor.builder("factor", PropertyType.NUMBER)
                    .displayName("Factor").references().defaultValue(1).build())
            .property(PropertyDescriptor.builder("offset", PropertyType.NUMBER)
                    .displayName("Offset").references().defaultValue(0).build())
            .relationship(Relationship.of("success", "The scaled series."))
            .accepts(ContentKind.TIMESERIES)
            .build();

    @Override
    public ProcessorDescriptor describe() {
        return DESCRIPTOR;
    }

    @Override
    public void onTrigger(ProcessContext context, ProcessSession session) {
        Item item = session.get();
        double factor = context.property("factor").evaluate(item).asDouble();
        double offset = context.property("offset").evaluate(item).asDouble();
        TimeseriesContent in = item.content(TimeseriesContent.class);
        TimeseriesContent.Builder out = session.newTimeseries(in.rowCount());
        for (int i = 0; i < in.rowCount(); i++) {
            out.add(in.timestamp(i), in.value(i) * factor + offset);
        }
        item = session.write(item, out.build());
        session.transfer(item, "success");
    }
}
