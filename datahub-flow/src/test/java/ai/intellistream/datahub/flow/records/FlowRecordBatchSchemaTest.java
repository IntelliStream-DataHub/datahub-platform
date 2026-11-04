// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.flow.records;

import org.apache.avro.JsonProperties;
import org.apache.avro.Schema;
import org.apache.avro.reflect.ReflectData;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link FlowRecordBatch} is published with {@code Schema.AVRO(...)}, so its Avro schema is derived
 * by reflection and a field change is a wire change: during a rolling deploy one build publishes
 * what another consumes. This pins the schema, and round-trips a batch through the schema the
 * producer and consumer actually use, since Avro reflection reports some mistakes only on decoding.
 */
class FlowRecordBatchSchemaTest {

    private static Schema unwrap(Schema schema) {
        return schema.getType() != Schema.Type.UNION ? schema
                : schema.getTypes().stream().filter(t -> t.getType() != Schema.Type.NULL).findFirst().orElseThrow();
    }

    private static Set<String> fields(Schema record) {
        return record.getFields().stream().map(Schema.Field::name).collect(Collectors.toSet());
    }

    @Test
    void theBatchSchemaIsPinned() {
        Schema batch = ReflectData.AllowNull.get().getSchema(FlowRecordBatch.class);

        assertThat(fields(batch)).isEqualTo(Set.of("runId", "attempt", "sequence", "flowExternalId",
                "flowVersion", "dataSetId", "records"));
        // Added after the topic's first schema: without a default, a batch from an older build would
        // not decode, and the broker would refuse the new schema.
        assertThat(batch.getField("dataSetId").defaultVal()).isEqualTo(JsonProperties.NULL_VALUE);
        Schema row = unwrap(unwrap(batch.getField("records").schema()).getElementType());
        assertThat(fields(row)).isEqualTo(Set.of("timeMillis", "processor", "processorType", "action",
                "itemId", "parentItemIds", "relationship", "contentKind", "size", "hash",
                "changedAttributes", "transitUri", "durationMillis", "truncated"));
        assertThat(unwrap(row.getField("timeMillis").schema()).getType()).isEqualTo(Schema.Type.LONG);
        assertThat(unwrap(row.getField("hash").schema()).getType()).isEqualTo(Schema.Type.LONG);
        assertThat(unwrap(row.getField("changedAttributes").schema()).getType()).isEqualTo(Schema.Type.MAP);
        assertThat(unwrap(row.getField("parentItemIds").schema()).getType()).isEqualTo(Schema.Type.ARRAY);
    }

    @Test
    void aBatchSurvivesTheWireSchema() {
        FlowRecordBatch batch = new FlowRecordBatch();
        batch.setRunId("0192a6b0-0000-7000-8000-000000000001");
        batch.setAttempt(2);
        batch.setSequence(5);
        batch.setFlowExternalId("temp_hourly");
        batch.setFlowVersion(7);
        batch.setDataSetId(42L);
        FlowRecordBatch.Row row = new FlowRecordBatch.Row();
        row.setTimeMillis(1_760_263_200_123L);
        row.setProcessor("out");
        row.setAction("send");
        row.setItemId("0192a6b0-0000-7000-8000-0000000000aa");
        row.setParentItemIds(List.of("0192a6b0-0000-7000-8000-0000000000bb"));
        row.setHash(-1);
        row.setChangedAttributes(Map.of("timeseries.externalId", "ts_out"));
        row.setTransitUri("datahub://timeseries/ts_out");
        batch.setRecords(List.of(row, FlowRecordBatch.Row.truncatedMarker(1)));

        var schema = org.apache.pulsar.client.api.Schema.AVRO(FlowRecordBatch.class);
        FlowRecordBatch decoded = schema.decode(schema.encode(batch));

        assertThat(decoded.getRunId()).isEqualTo(batch.getRunId());
        assertThat(decoded.getSequence()).isEqualTo(5);
        assertThat(decoded.getDataSetId()).isEqualTo(42L);
        assertThat(decoded.getRecords()).hasSize(2);
        FlowRecordBatch.Row first = decoded.getRecords().getFirst();
        assertThat(first.getHash()).isEqualTo(-1);
        assertThat(first.getRelationship()).isNull();
        assertThat(first.getParentItemIds()).containsExactly("0192a6b0-0000-7000-8000-0000000000bb");
        assertThat(first.getChangedAttributes()).containsEntry("timeseries.externalId", "ts_out");
        assertThat(decoded.getRecords().getLast().isTruncated()).isTrue();
    }
}
