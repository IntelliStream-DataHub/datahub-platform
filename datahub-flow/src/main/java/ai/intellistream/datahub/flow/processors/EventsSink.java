// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.flow.processors;

import ai.intellistream.datahub.flow.api.DynamicPropertyDescriptor;
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
import ai.intellistream.datahub.flow.api.PropertyValue;
import ai.intellistream.datahub.flow.api.Relationship;
import ai.intellistream.datahub.models.DataSetModel;
import ai.intellistream.datahub.models.EventModel;
import ai.intellistream.datahub.models.IdCollection;
import ai.intellistream.datahub.sdk.http.DatahubApiException;
import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * {@code datahub.events.sink}: creates one DataHub event per row of an {@code events} item. Type,
 * subtype, dataset and description come from here; {@code externalId}, {@code eventTime} and
 * {@code status} from the row; metadata from the row plus the dynamic properties. Rows sharing an
 * external id are one event's lifecycle, as everywhere in DataHub.
 *
 * <p>Each event's id is derived from what the event says — its external id, time, status, type and
 * subtype — shaped as a UUID v7 on the event's time. So a retried write, or a re-run of the same
 * window that finds the same transition, carries the same id and collapses in ClickHouse instead of
 * adding a duplicate, while ids stay ordered by time as the events table's sort key wants.
 */
@Component
public class EventsSink implements Processor {

    static final String TYPE = "datahub.events.sink";

    /** Events per create request. */
    private static final int BATCH = 1000;

    private static final ProcessorDescriptor DESCRIPTOR = ProcessorDescriptor
            .builder(TYPE, "1.0.0")
            .displayName("Create events")
            .description("Creates one DataHub event per row of an events item.")
            .property(PropertyDescriptor.builder("type", PropertyType.STRING)
                    .displayName("Type").description("The events' type.")
                    .required().references().build())
            .property(PropertyDescriptor.builder("subType", PropertyType.STRING)
                    .displayName("Subtype").description("The events' subtype.")
                    .references().build())
            .property(PropertyDescriptor.builder("externalId", PropertyType.STRING)
                    .displayName("External id")
                    .description("Overrides the row's external id, e.g. to key a lifecycle by "
                            + "series: ${timeseries.externalId}:high. The row's when unset.")
                    .references().build())
            .property(PropertyDescriptor.builder("dataSet", PropertyType.STRING)
                    .displayName("Dataset")
                    .description("External id of the events' dataset: any dataset the run's "
                            + "identity may write. The flow's own when unset.")
                    .build())
            .property(PropertyDescriptor.builder("description", PropertyType.STRING)
                    .displayName("Description").description("The events' description.")
                    .references().build())
            .dynamicProperties(new DynamicPropertyDescriptor(
                    "A metadata key on the events.",
                    "Its value, e.g. ${timeseries.externalId}.",
                    PropertyType.STRING, true, false))
            .relationship(new Relationship("success", "Created; the item passes on unchanged.",
                    Relationship.Unconnected.TERMINATE))
            .relationship(new Relationship("failure", "Not created; failure.reason says why. "
                    + "Left unconnected, a failed write fails the run.",
                    Relationship.Unconnected.FAIL_RUN))
            .build();

    @Override
    public ProcessorDescriptor describe() {
        return DESCRIPTOR;
    }

    @Override
    public void onTrigger(ProcessContext context, ProcessSession session) {
        Item item = session.get();
        List<EventRow> rows = item.content(EventsContent.class).rows();
        String type = context.property("type").evaluate(item).asString();
        String subType = context.property("subType").evaluate(item).asString();
        String externalId = context.property("externalId").evaluate(item).asString();
        String description = context.property("description").evaluate(item).asString();
        Map<String, String> metadata = new LinkedHashMap<>();
        for (Map.Entry<String, PropertyValue> e : context.dynamicProperties().entrySet()) {
            metadata.put(e.getKey(), e.getValue().evaluate(item).asString());
        }

        if (context.isPreview()) {
            context.log().info("Would create " + rows.size() + " events of type " + type);
            item = session.putAllAttributes(item, Map.of("preview.type", type,
                    "preview.events", Integer.toString(rows.size())));
            session.transfer(item, "success");
            return;
        }
        if (rows.isEmpty()) {
            session.transfer(item, "success");
            return;
        }

        Long dataSetId = dataSet(context);
        if (dataSetId == null) {
            item = session.putAttribute(item, "failure.reason", "Dataset "
                    + context.property("dataSet").asString() + " does not exist");
            session.transfer(item, "failure");
            return;
        }

        List<EventModel> events = new ArrayList<>(rows.size());
        Instant first = rows.getFirst().eventTime();
        Instant last = first;
        for (EventRow row : rows) {
            Map<String, String> meta = new LinkedHashMap<>(row.metadata());
            meta.putAll(metadata);
            EventModel event = new EventModel();
            event.setExternalId(externalId != null ? externalId : row.externalId());
            event.setEventTime(row.eventTime().toEpochMilli());
            event.setStatus(row.status().isEmpty() ? null : row.status());
            event.setType(type);
            event.setSubType(subType);
            event.setDescription(description);
            event.setDataSetId(dataSetId);
            event.setMetadata(meta);
            event.setId(id(event, row.eventTime().toEpochMilli()).toString());
            events.add(event);
            first = row.eventTime().isBefore(first) ? row.eventTime() : first;
            last = row.eventTime().isAfter(last) ? row.eventTime() : last;
        }

        try {
            for (int i = 0; i < events.size(); i += BATCH) {
                context.datahub().events().create(events.subList(i, Math.min(events.size(), i + BATCH)));
            }
        } catch (DatahubApiException e) {
            if (!TimeseriesSink.refused(e)) {
                throw new ProcessException("Creating events failed: " + e.getMessage(), e);
            }
            item = session.putAttribute(item, "failure.reason", "DataHub refused the events ("
                    + e.statusCode() + "): " + TimeseriesSink.detail(e));
            session.transfer(item, "failure");
            return;
        }
        session.send(item, TransitUris.events(type, subType, first, last.plusMillis(1)));
        session.transfer(item, "success");
    }

    /** The events' dataset: the one named, or else the flow's; null when the named one is gone. */
    private static Long dataSet(ProcessContext context) {
        String externalId = context.property("dataSet").asString();
        if (externalId == null) {
            if (context.dataSetId() == null) {
                throw new ProcessException("Name a dataSet: this flow has none of its own");
            }
            return context.dataSetId();
        }
        List<DataSetModel> found;
        try {
            found = context.datahub().datasets()
                    .byIds(List.of(IdCollection.createFromExternalId(externalId))).getItems();
        } catch (DatahubApiException e) {
            throw new ProcessException("Could not look up dataset " + externalId + ": "
                    + e.getMessage(), e);
        }
        return found == null || found.isEmpty() ? null : found.getFirst().getId();
    }

    /**
     * A UUID v7 whose time is the event's and whose remaining bits are a hash of what the event
     * says, so the same transition always gets the same id.
     */
    static UUID id(EventModel event, long eventTimeMillis) {
        byte[] hash;
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            for (String part : new String[]{event.getExternalId(), Long.toString(eventTimeMillis),
                    event.getStatus(), event.getType(), event.getSubType()}) {
                sha.update(String.valueOf(part).getBytes(StandardCharsets.UTF_8));
                sha.update((byte) 0);
            }
            hash = sha.digest();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        ByteBuffer h = ByteBuffer.wrap(hash);
        long msb = (eventTimeMillis << 16) | 0x7000L | (h.getShort() & 0x0FFFL);
        long lsb = (h.getLong() & 0x3FFFFFFFFFFFFFFFL) | 0x8000000000000000L;
        return new UUID(msb, lsb);
    }
}
