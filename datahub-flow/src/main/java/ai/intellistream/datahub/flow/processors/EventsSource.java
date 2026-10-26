// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.flow.processors;

import ai.intellistream.datahub.api.responses.DataWrapper;
import ai.intellistream.datahub.flow.api.EventsContent;
import ai.intellistream.datahub.flow.api.EventsContent.EventRow;
import ai.intellistream.datahub.flow.api.Item;
import ai.intellistream.datahub.flow.api.ProcessContext;
import ai.intellistream.datahub.flow.api.ProcessException;
import ai.intellistream.datahub.flow.api.ProcessSession;
import ai.intellistream.datahub.flow.api.Processor;
import ai.intellistream.datahub.flow.api.ProcessorDescriptor;
import ai.intellistream.datahub.flow.api.PropertyDescriptor;
import ai.intellistream.datahub.flow.api.PropertyType;
import ai.intellistream.datahub.flow.api.PropertyValues;
import ai.intellistream.datahub.flow.api.Relationship;
import ai.intellistream.datahub.flow.api.TimeWindow;
import ai.intellistream.datahub.models.EventModel;
import ai.intellistream.datahub.models.datafilters.TimeFilter;
import ai.intellistream.datahub.models.events.EventFilter;
import ai.intellistream.datahub.models.events.EventRetreiver;
import ai.intellistream.datahub.sdk.http.DatahubApiException;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * {@code read-events}: reads the events whose event time is in the run's window, one {@code events} item
 * of at most {@code maxEventsPerItem} rows at a time, oldest first. Optionally only some types,
 * subtypes or statuses. A row carries the event's external id, time, status and metadata, which is
 * what {@code write-events} writes.
 *
 * <p>The api's time filter includes both ends, so events exactly at the window's end are dropped
 * here: they belong to the next window.
 */
@Component
public class EventsSource implements Processor {

    static final String TYPE = "read-events";
    /** Events asked for per call; the api's maximum is 10 000. */
    private static final int PAGE = 1000;

    private static final ProcessorDescriptor DESCRIPTOR = ProcessorDescriptor
            .builder(TYPE, "1.0.0")
            .displayName("Read events")
            .category(ProcessorDescriptor.Category.READ_WRITE)
            .description("Reads the events in the run's window from DataHub, oldest first.")
            .property(PropertyDescriptor.builder("type", PropertyType.LIST)
                    .displayName("Types")
                    .description("Event types to read; every type when unset.").build())
            .property(PropertyDescriptor.builder("subType", PropertyType.LIST)
                    .displayName("Subtypes")
                    .description("Event subtypes to read; every subtype when unset.")
                    .advanced().build())
            .property(PropertyDescriptor.builder("status", PropertyType.LIST)
                    .displayName("Statuses")
                    .description("Statuses to read; every status when unset.")
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
    public List<String> validate(PropertyValues properties) {
        Double max = Literal.number(properties.property("maxEventsPerItem"));
        return max != null && max < 1 ? List.of("maxEventsPerItem must be at least 1.") : List.of();
    }

    @Override
    public void onTrigger(ProcessContext context, ProcessSession session) {
        TimeWindow runWindow = context.window()
                .orElseThrow(() -> new ProcessException("The run has no window to read"));
        Instant from = runWindow.start();
        Instant to = runWindow.end();
        int maxRows = context.property("maxEventsPerItem").asDouble().intValue();

        EventFilter filter = new EventFilter();
        filter.setType(context.property("type").asList());
        filter.setSubType(context.property("subType").asList());
        filter.setStatus(context.property("status").asList());
        TimeFilter window = new TimeFilter();
        window.setMin(from.atZone(ZoneOffset.UTC));
        window.setMax(to.atZone(ZoneOffset.UTC));
        filter.setEventTime(window);

        List<EventRow> rows = new ArrayList<>();
        String cursor = null;
        do {
            EventRetreiver request = new EventRetreiver();
            request.setFilter(filter);
            request.setLimit(PAGE);
            request.setCursor(cursor);
            DataWrapper<EventModel> page;
            try {
                page = context.datahub().events().filter(request);
            } catch (DatahubApiException e) {
                throw new ProcessException("Could not read the events: " + e.getMessage(), e);
            }
            for (EventModel event : page.getItems() == null ? List.<EventModel>of() : page.getItems()) {
                Instant time = event.getEventTime() == null ? null : event.getEventTime().toInstant();
                if (time == null || !time.isBefore(to)) {
                    continue;
                }
                rows.add(new EventRow(event.getExternalId() != null ? event.getExternalId() : event.getId(),
                        time, event.getStatus(), event.getMetadata()));
            }
            cursor = page.getNextCursor();
        } while (cursor != null);
        rows.sort(Comparator.comparing(EventRow::eventTime));
        // What was asked for, for the receive record: * for every type.
        String uri = TransitUris.events(filter.getType() == null ? "*" : String.join(",", filter.getType()),
                filter.getSubType() == null ? null : String.join(",", filter.getSubType()), from, to);

        for (int start = 0; start < rows.size(); start += maxRows) {
            Item item = session.create();
            item = session.putAllAttributes(item, Map.of(Points.WINDOW_START, from.toString(),
                    Points.WINDOW_END, to.toString()));
            item = session.write(item, EventsContent.of(rows.subList(start, Math.min(rows.size(), start + maxRows))));
            session.receive(item, uri);
            session.transfer(item, "success");
        }
    }
}
