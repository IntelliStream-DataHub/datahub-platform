// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.clickhouse;

import ai.intellistream.datahub.api.binary.DatapointValueType;
import ai.intellistream.datahub.jpa.domains.TimeseriesValueType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The wire contract's value types and the server's entity must agree on ids and table names: a
 * frame's valueTypeId is what the consumer uses to pick the ClickHouse table.
 */
class DatapointValueTypeParityTest {

    @Test
    void idsAndTablesMatchTheEntity() {
        for (DatapointValueType t : DatapointValueType.values()) {
            assertEquals(TimeseriesValueType.getValueTypeId(t.name()), t.id(), t.name());
            assertEquals("datapoints_" + TimeseriesValueType.getTableType(t.name()), t.tableName(), t.name());
            assertEquals(t, DatapointValueType.fromName(t.name().toLowerCase()));
        }
    }
}
