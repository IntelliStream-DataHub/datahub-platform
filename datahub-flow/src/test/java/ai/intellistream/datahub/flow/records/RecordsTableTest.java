// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.flow.records;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** What the records consumer sends ClickHouse for a batch: one JSONEachRow line per record. */
class RecordsTableTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    void aBatchBecomesOneLinePerRecordInOrder() {
        FlowRecordBatch batch = batch();

        String[] lines = new String(RecordsTable.rows(batch), StandardCharsets.UTF_8).split("\n");

        assertThat(lines).hasSize(2);
        JsonNode first = JSON.readTree(lines[0]);
        assertThat(first.get("event_time").asString()).isEqualTo("2025-10-12 10:00:00.123");
        assertThat(first.get("run_id").asString()).isEqualTo(batch.getRunId());
        assertThat(first.get("attempt").asInt()).isEqualTo(2);
        assertThat(first.get("sequence").asInt()).isEqualTo(3);
        assertThat(first.get("ordinal").asInt()).isZero();
        assertThat(JSON.readTree(lines[1]).get("ordinal").asInt()).isEqualTo(1);
        assertThat(JSON.readTree(lines[1]).get("action").asString()).isEqualTo("truncated");
        assertThat(first.get("flow_external_id").asString()).isEqualTo("temp_hourly");
        assertThat(first.get("data_set_id").asLong()).isEqualTo(42);
        assertThat(first.get("parent_item_ids").get(0).asString())
                .isEqualTo("0192a6b0-0000-7000-8000-0000000000bb");
        assertThat(first.get("changed_attributes").get("k").asString()).isEqualTo("v");
    }

    @Test
    void theHashIsWrittenUnsignedAndMissingTextAsEmpty() {
        JsonNode row = JSON.readTree(new String(RecordsTable.rows(batch()), StandardCharsets.UTF_8)
                .split("\n")[0]);

        // ClickHouse's hash column is UInt64; the Java long holding it is negative above 2^63.
        assertThat(row.get("hash").bigIntegerValue().toString()).isEqualTo("18446744073709551615");
        assertThat(row.get("relationship").asString()).isEmpty();
        assertThat(row.get("content_kind").asString()).isEmpty();
    }

    @Test
    void aBatchFromABuildBeforeTheDatasetHasDatasetZero() {
        FlowRecordBatch batch = batch();
        batch.setDataSetId(null);

        JsonNode row = JSON.readTree(new String(RecordsTable.rows(batch), StandardCharsets.UTF_8).split("\n")[0]);

        assertThat(row.get("data_set_id").asLong()).isZero();
    }

    @Test
    void theDeduplicationTokenNamesTheBatch() {
        assertThat(RecordsTable.token(batch())).isEqualTo("0192a6b0-0000-7000-8000-000000000001:2:3");
    }

    private static FlowRecordBatch batch() {
        FlowRecordBatch batch = new FlowRecordBatch();
        batch.setRunId("0192a6b0-0000-7000-8000-000000000001");
        batch.setAttempt(2);
        batch.setSequence(3);
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
        row.setChangedAttributes(Map.of("k", "v"));
        batch.setRecords(List.of(row, FlowRecordBatch.Row.truncatedMarker(1_760_263_200_124L)));
        return batch;
    }
}
