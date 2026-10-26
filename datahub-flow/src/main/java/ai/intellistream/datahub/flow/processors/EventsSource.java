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
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * {@code read-events}: reads the events whose event time is in the run's window, one {@code events} item
 * of at most {@code maxEventsPerItem} rows at a time, oldest first. Optionally only some types,
 * subtypes or statuses. A row carries the event's external id, time, status and metadata, which is
 * what {@code write-events} writes.
 *
 * <p>The api's time filter includes both ends, so events exactly at the window's end are dropped
 * here: they belong to the next window.
 *
 * <p>The window bounds the event time, or with {@code timeField: createdTime} the time the event
 * was recorded, so a run sees what landed since the last one: a producer's late-stamped event, a
 * backfill, a status change on an older lifecycle. {@code excludeSource} leaves out events by their
 * source, which is how an agent never reads what an agent wrote.
 */
@Component
public class EventsSource implements Processor {

    static final String TYPE = "read-events";
    /** Events asked for per call; the api's maximum is 10 000. */
    private static final int PAGE = 1000;
    static final String EVENT_TIME = "eventTime";
    static final String CREATED_TIME = "createdTime";
    /** What a source pattern may hold: a source name's characters and the wildcard. */
    private static final Pattern SOURCE_PATTERN = Pattern.compile("[A-Za-z0-9_.:*-]+");

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
            .property(PropertyDescriptor.builder("timeField", PropertyType.ENUM)
                    .displayName("Time field")
                    .description("Which time the window bounds: when the event happened (eventTime), "
                            + "or when DataHub recorded it (createdTime), which also catches "
                            + "late-stamped events and status changes on older ones.")
                    .allowableValues(new PropertyDescriptor.AllowableValue(EVENT_TIME, "Event time", "When the event happened."),
                            new PropertyDescriptor.AllowableValue(CREATED_TIME, "Recorded time", "When DataHub recorded it."))
                    .defaultValue(EVENT_TIME).advanced().build())
            .property(PropertyDescriptor.builder("excludeSource", PropertyType.LIST)
                    .displayName("Exclude sources")
                    .description("Events whose source matches any of these are left out; * matches "
                            + "anything, _ one character. An agent's own events are agent:*.")
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
        List<String> problems = new ArrayList<>();
        Double max = Literal.number(properties.property("maxEventsPerItem"));
        if (max != null && max < 1) {
            problems.add("maxEventsPerItem must be at least 1.");
        }
        List<String> exclude = Literal.list(properties.property("excludeSource"));
        if (exclude != null) {
            for (String pattern : exclude) {
                if (pattern == null || !SOURCE_PATTERN.matcher(pattern).matches()) {
                    problems.add("excludeSource: \"" + pattern + "\" is not a source pattern: letters, "
                            + "digits, _ . : - and the wildcard *.");
                }
            }
        }
        return problems;
    }

    @Override
    public void onTrigger(ProcessContext context, ProcessSession session) {
        TimeWindow runWindow = context.window()
                .orElseThrow(() -> new ProcessException("The run has no window to read"));
        Instant from = runWindow.start();
        Instant to = runWindow.end();
        int maxRows = context.property("maxEventsPerItem").asDouble().intValue();
        boolean byCreated = CREATED_TIME.equals(context.property("timeField").asString());

        EventFilter filter = new EventFilter();
        filter.setType(context.property("type").asList());
        filter.setSubType(context.property("subType").asList());
        filter.setStatus(context.property("status").asList());
        TimeFilter window = new TimeFilter();
        window.setMin(from.atZone(ZoneOffset.UTC));
        window.setMax(to.atZone(ZoneOffset.UTC));
        if (byCreated) {
            filter.setCreatedTime(window);
        } else {
            filter.setEventTime(window);
        }
        // The exclusion goes as an expression the api parses and binds; * is SQL's %, and the
        // patterns were checked at save time (no quote can be in one). An event with no source is
        // kept: the guard is for a store that keeps that as null.
        List<String> exclude = context.property("excludeSource").asList();
        String advanced = exclude == null || exclude.isEmpty() ? null : exclude.stream()
                .map(p -> "(source IS NULL OR source NOT LIKE '" + p.replace("*", "%") + "')")
                .collect(Collectors.joining(" AND "));

        List<EventRow> rows = new ArrayList<>();
        String cursor = null;
        do {
            EventRetreiver request = new EventRetreiver();
            request.setFilter(filter);
            request.setAdvancedFilter(advanced);
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
                Instant bounded = byCreated
                        ? (event.getCreatedTime() == null ? null : event.getCreatedTime().toInstant()) : time;
                if (time == null || bounded == null || !bounded.isBefore(to)) {
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
