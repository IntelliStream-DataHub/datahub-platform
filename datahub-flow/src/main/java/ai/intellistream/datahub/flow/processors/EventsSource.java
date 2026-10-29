// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.flow.processors;

import ai.intellistream.datahub.api.responses.DataWrapper;
import ai.intellistream.datahub.flow.api.EventMatch;
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
import ai.intellistream.datahub.models.IdCollection;
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

/**
 * {@code read-events}: reads the events whose event time is in the run's window, one {@code events} item
 * of at most {@code maxEventsPerItem} rows at a time, oldest first. Optionally only some types,
 * subtypes, statuses or external ids. A row carries the event's external id, time, status and metadata, which is
 * what {@code write-events} writes.
 *
 * <p>The api's time filter includes both ends, so events exactly at the window's end are dropped
 * here: they belong to the next window.
 *
 * <p>The window bounds the event time, or with {@code timeField: createdTime} the time the event
 * was recorded, so a run sees what landed since the last one: a producer's late-stamped event, a
 * backfill, a status change on an older lifecycle. {@code excludeSource} leaves out events by their
 * source, which is how an agent never reads what an agent wrote. The other filters are the events
 * page's, less its time: source, dataset, metadata and an advanced expression, which is ANDed in
 * parentheses with the exclusions.
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
            .property(PropertyDescriptor.builder("externalId", PropertyType.LIST)
                    .displayName("External ids")
                    .description("Events to read by external id, each one lifecycle such as one "
                            + "alarm; * matches anything, so pump-7/* is a family. Every event when unset.")
                    .advanced().build())
            .property(PropertyDescriptor.builder("source", PropertyType.LIST)
                    .displayName("Sources")
                    .description("Sources to read; every source when unset.")
                    .advanced().build())
            .property(PropertyDescriptor.builder("dataSet", PropertyType.LIST)
                    .displayName("Datasets")
                    .description("Datasets to read from, by external id, each with the datasets "
                            + "beneath it; every dataset the run may read when unset.")
                    .advanced().build())
            .property(PropertyDescriptor.builder("metadata", PropertyType.LIST)
                    .displayName("Metadata")
                    .description("Metadata entries that must all be present, as key=value; a key "
                            + "alone matches any value.")
                    .advanced().build())
            .property(PropertyDescriptor.builder("advancedFilter", PropertyType.STRING)
                    .displayName("Advanced filter")
                    .description("An expression over the events' fields, as in the events page's "
                            + "advanced filter.")
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
        List<String> metadata = Literal.list(properties.property("metadata"));
        if (metadata != null) {
            for (String entry : metadata) {
                if (entry == null || entry.isBlank() || entry.strip().startsWith("=")) {
                    problems.add("metadata: \"" + entry + "\" is not key=value or a key.");
                }
            }
        }
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

    private static IdCollection byExternalId(String externalId) {
        IdCollection id = new IdCollection();
        id.setExternalId(externalId);
        return id;
    }

    @Override
    public void onTrigger(ProcessContext context, ProcessSession session) {
        EventMatch wanted = new EventMatch(context.property("externalId").asList(), context.property("type").asList(),
                context.property("subType").asList(), context.property("status").asList(),
                context.property("source").asList(), context.property("metadata").asList(),
                context.property("excludeSource").asList());
        read(context, session, wanted, context.property("dataSet").asList(), context.property("advancedFilter").asString(),
                CREATED_TIME.equals(context.property("timeField").asString()),
                context.property("maxEventsPerItem").asDouble().intValue());
    }

    /**
     * Reads the events {@code wanted} and the rest ask for in the run's window, oldest first, and sends
     * them on {@code success} in items of at most {@code maxRows}.
     *
     * @param dataSets  dataset external ids, each with those beneath it; null or empty for any
     * @param own       an advanced filter expression, ANDed in parentheses
     * @param byCreated whether the window bounds when an event was recorded rather than when it happened
     */
    static void read(ProcessContext context, ProcessSession session, EventMatch wanted, List<String> dataSets,
                     String own, boolean byCreated, int maxRows) {
        EventFilter filter = wanted.filter();
        List<String> exclude = wanted.excludeSource();
        TimeWindow runWindow = context.window()
                .orElseThrow(() -> new ProcessException("The run has no window to read"));
        Instant from = runWindow.start();
        Instant to = runWindow.end();
        // Null is no restriction; an empty list would match no events at all.
        filter.setDataSetId(dataSets == null || dataSets.isEmpty() ? null
                : dataSets.stream().map(EventsSource::byExternalId).toList());
        TimeFilter window = new TimeFilter();
        window.setMin(from.atZone(ZoneOffset.UTC));
        window.setMax(to.atZone(ZoneOffset.UTC));
        if (byCreated) {
            filter.setCreatedTime(window);
        } else {
            filter.setEventTime(window);
        }
        // The exclusion goes as an expression the api parses and binds. The user's own expression goes
        // in parentheses, so an OR in it cannot undo an exclusion.
        List<String> clauses = new ArrayList<>();
        if (own != null && !own.isBlank()) {
            clauses.add("(" + own.strip() + ")");
        }
        clauses.addAll(EventMatch.exclusions(exclude));
        String advanced = clauses.isEmpty() ? null : String.join(" AND ", clauses);

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
