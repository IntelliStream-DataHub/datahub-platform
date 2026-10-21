// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.flow.records;

import ai.intellistream.datahub.flow.engine.FlowRecord;
import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * One message on a tenant's records topic: one batch of one run attempt's records, published with
 * {@code Schema.AVRO(FlowRecordBatch.class)} and keyed by the run id.
 *
 * <p>The schema is derived by reflection, so a field change here is a wire change: instances that
 * publish and instances that consume can run different builds during a rolling deploy.
 * {@code FlowRecordBatchSchemaTest} pins it. Only types Avro reflects without help — strings,
 * numbers, lists and string maps — so a run id travels as text and a time as epoch milliseconds.
 * {@code dataSetId} is boxed so that its schema says {@code "default": null}: a batch from a build
 * before it reads as one without a dataset, and the topic's registered schema accepts the change.
 *
 * <p>{@code sequence} numbers the batches of one attempt from 0. With the run id and the attempt it
 * names the batch, which is what the consumer's insert deduplicates on: a batch inserted but not yet
 * acknowledged when its consumer died is redelivered and must not be stored twice.
 */
@Getter
@Setter
public class FlowRecordBatch {

    private String runId;
    private int attempt;
    private int sequence;
    private String flowExternalId;
    private int flowVersion;
    private Long dataSetId;
    private List<Row> records = new ArrayList<>();

    /** One record: one thing a processor did to an item. See {@link FlowRecord}. */
    @Getter
    @Setter
    public static class Row {

        private long timeMillis;
        private String processor;
        private String processorType;
        /** {@link FlowRecord.Action} in lower case, or {@value #TRUNCATED} for the cap's marker. */
        private String action;
        private String itemId;
        private List<String> parentItemIds = new ArrayList<>();
        private String relationship;
        private String contentKind;
        private long size;
        private long hash;
        private Map<String, String> changedAttributes = new HashMap<>();
        private String transitUri;
        private long durationMillis;
        private boolean truncated;

        /** The action of the marker row that ends an attempt which hit the per-run record cap. */
        public static final String TRUNCATED = "truncated";

        static Row of(FlowRecord record) {
            Row row = new Row();
            row.timeMillis = record.time().toEpochMilli();
            row.processor = record.processorId();
            row.processorType = record.processorType();
            row.action = record.action().name().toLowerCase(java.util.Locale.ROOT);
            row.itemId = record.itemId().toString();
            for (UUID parent : record.parentItemIds()) {
                row.parentItemIds.add(parent.toString());
            }
            row.relationship = record.relationship();
            row.contentKind = record.contentKind() == null ? null
                    : record.contentKind().name().toLowerCase(java.util.Locale.ROOT);
            row.size = record.size();
            row.hash = record.hash();
            row.changedAttributes.putAll(record.changedAttributes());
            row.transitUri = record.transitUri();
            row.durationMillis = record.durationMillis();
            return row;
        }

        /** The last row of an attempt that hit the cap: says that records after it were dropped. */
        static Row truncatedMarker(long timeMillis) {
            Row row = new Row();
            row.timeMillis = timeMillis;
            row.action = TRUNCATED;
            row.itemId = new UUID(0, 0).toString();
            row.truncated = true;
            return row;
        }
    }
}
