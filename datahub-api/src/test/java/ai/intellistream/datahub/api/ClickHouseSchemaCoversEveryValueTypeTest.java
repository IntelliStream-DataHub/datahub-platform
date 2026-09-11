// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api;

import ai.intellistream.datahub.api.binary.DatapointValueType;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every value type needs the table its datapoints are written to. This module owns the ClickHouse
 * schema each tenant is created from, so a type added to the catalogue without a table lands here.
 */
class ClickHouseSchemaCoversEveryValueTypeTest {

    @Test
    void everyValueTypeHasItsTable() throws IOException {
        Path schemaFile = Path.of("src/main/resources/db/clickhouse.sql");
        assertTrue(Files.isRegularFile(schemaFile), "no ClickHouse schema at " + schemaFile.toAbsolutePath());
        String schema = Files.readString(schemaFile);
        for (DatapointValueType type : DatapointValueType.values()) {
            assertTrue(schema.contains(type.tableName()),
                    type + " has no " + type.tableName() + " table in clickhouse.sql");
        }
    }
}
