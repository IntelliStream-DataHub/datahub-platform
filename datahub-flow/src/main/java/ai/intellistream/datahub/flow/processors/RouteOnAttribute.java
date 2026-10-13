// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.flow.processors;

import ai.intellistream.datahub.flow.api.DynamicPropertyDescriptor;
import ai.intellistream.datahub.flow.api.Item;
import ai.intellistream.datahub.flow.api.ProcessContext;
import ai.intellistream.datahub.flow.api.ProcessSession;
import ai.intellistream.datahub.flow.api.Processor;
import ai.intellistream.datahub.flow.api.ProcessorDescriptor;
import ai.intellistream.datahub.flow.api.PropertyDescriptor;
import ai.intellistream.datahub.flow.api.PropertyType;
import ai.intellistream.datahub.flow.api.PropertyValue;
import ai.intellistream.datahub.flow.api.Relationship;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * {@code route.on.attribute}: routes whole items by the value of one attribute. Each dynamic
 * property names a relationship and the value that selects it; the first match in definition order
 * wins, and an item that matches none goes to {@code unmatched}.
 */
@Component
public class RouteOnAttribute implements Processor {

    private static final ProcessorDescriptor DESCRIPTOR = ProcessorDescriptor
            .builder("route.on.attribute", "1.0.0")
            .displayName("Route by attribute")
            .description("Routes whole items by the value of one attribute.")
            .property(PropertyDescriptor.builder("attribute", PropertyType.STRING)
                    .displayName("Attribute").description("The attribute to look at.")
                    .required().defaultValue(Points.EXTERNAL_ID).build())
            .dynamicProperties(new DynamicPropertyDescriptor(
                    "A relationship name.",
                    "The attribute value that selects it.",
                    PropertyType.STRING, false, true))
            .relationship(Relationship.of("unmatched", "Items whose value selects no relationship."))
            .build();

    @Override
    public ProcessorDescriptor describe() {
        return DESCRIPTOR;
    }

    @Override
    public void onTrigger(ProcessContext context, ProcessSession session) {
        Item item = session.get();
        String value = item.attribute(context.property("attribute").asString());
        String relationship = "unmatched";
        if (value != null) {
            for (Map.Entry<String, PropertyValue> route : context.dynamicProperties().entrySet()) {
                if (value.equals(route.getValue().asString())) {
                    relationship = route.getKey();
                    break;
                }
            }
        }
        session.transfer(item, relationship);
    }
}
