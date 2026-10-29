// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.flow.processors;

import ai.intellistream.datahub.flow.api.EventMatch;
import ai.intellistream.datahub.flow.api.ProcessContext;
import ai.intellistream.datahub.flow.api.ProcessException;
import ai.intellistream.datahub.flow.api.ProcessSession;
import ai.intellistream.datahub.flow.api.Processor;
import ai.intellistream.datahub.flow.api.ProcessorDescriptor;
import ai.intellistream.datahub.flow.api.PropertyDescriptor;
import ai.intellistream.datahub.flow.api.PropertyType;
import ai.intellistream.datahub.flow.api.Relationship;
import ai.intellistream.datahub.flow.definition.FlowValidator;
import org.springframework.stereotype.Component;

/**
 * {@code trigger-events}: reads the events that triggered the run. The filter is the flow's events
 * trigger's own, so what wakes the flow and what it reads cannot drift apart; the window is the run's,
 * by when the events were recorded, so each run reads what was recorded since the one before. The run
 * reads as its own identity, so it sees only the events that identity may read.
 *
 * <p>Taking the events into the flow is a choice: a flow that only needs to know that something
 * happened has no such step. This one may narrow what it reads (datasets, an advanced filter) beyond
 * what the trigger listens for.
 */
@Component
public class TriggerEventsSource implements Processor {

    static final String TYPE = FlowValidator.TRIGGER_EVENTS;

    private static final ProcessorDescriptor DESCRIPTOR = ProcessorDescriptor
            .builder(TYPE, "1.0.0")
            .displayName("Triggering events")
            .category(ProcessorDescriptor.Category.READ_WRITE)
            .description("Reads the events that triggered the run: those the flow's events trigger listens "
                    + "for, recorded since the run before, oldest first.")
            .property(PropertyDescriptor.builder("dataSet", PropertyType.LIST)
                    .displayName("Only datasets")
                    .description("Read only the triggering events in these datasets, by external id, each "
                            + "with the datasets beneath it; every dataset the run may read when unset.")
                    .advanced().build())
            .property(PropertyDescriptor.builder("advancedFilter", PropertyType.STRING)
                    .displayName("Only matching")
                    .description("Read only the triggering events this expression over the events' fields "
                            + "matches, as in the events page's advanced filter.")
                    .advanced().build())
            .property(PropertyDescriptor.builder("maxEventsPerItem", PropertyType.NUMBER)
                    .displayName("Max events per item")
                    .description("More events are split into several items.")
                    .defaultValue(10_000).hidden().build())
            .relationship(Relationship.of("success", "The events read; none is no item."))
            .build();

    @Override
    public ProcessorDescriptor describe() {
        return DESCRIPTOR;
    }

    @Override
    public void onTrigger(ProcessContext context, ProcessSession session) {
        EventMatch match = context.triggerMatch().orElseThrow(() ->
                new ProcessException("The flow's trigger is not events, so no events triggered the run"));
        EventsSource.read(context, session, match, context.property("dataSet").asList(),
                context.property("advancedFilter").asString(), true,
                context.property("maxEventsPerItem").asDouble().intValue());
    }
}
