// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.clickhouse;

import ai.intellistream.datahub.api.binary.DatapointValueType;
import ai.intellistream.datahub.jpa.domains.TimeseriesValueType;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link DatapointValueType} is the one catalogue, but two mirrors of it cannot be removed: the
 * entity's {@code int} constants, which a {@code switch} case label needs as compile-time
 * constants, and the rows Flyway seeds. This pins both.
 */
class DatapointValueTypeParityTest {

    private static final Path MIGRATIONS = Path.of("src/main/resources/db/migration");

    /** The seven constants the hot-path switches use must be the enum's ids. */
    @Test
    void entityConstantsAreTheEnumIds() {
        assertEquals(DatapointValueType.BIGINT.id(), TimeseriesValueType.BIGINT);
        assertEquals(DatapointValueType.FLOAT.id(), TimeseriesValueType.FLOAT);
        assertEquals(DatapointValueType.NUMERIC.id(), TimeseriesValueType.NUMERIC);
        assertEquals(DatapointValueType.TEXT.id(), TimeseriesValueType.TEXT);
        assertEquals(DatapointValueType.DECIMAL32.id(), TimeseriesValueType.DECIMAL32);
        assertEquals(DatapointValueType.MIXED.id(), TimeseriesValueType.MIXED);
        assertEquals(DatapointValueType.FLOAT32.id(), TimeseriesValueType.FLOAT32);
    }

    /**
     * The enum must be exactly what a migrated database holds. An eighth type added by a Flyway
     * script but not to the enum lands here, which is the drift this catalogue exists to stop.
     */
    @Test
    void theEnumMatchesTheRowsFlywaySeeds() throws IOException {
        Map<Integer, String> seeded = seededValueTypes();
        Map<Integer, String> fromEnum = new TreeMap<>();
        for (DatapointValueType t : DatapointValueType.values()) {
            fromEnum.put(t.id(), t.name());
        }
        assertEquals(fromEnum, seeded);
    }

    /** Replays the value-type inserts and renames of every migration, in version order. */
    private static Map<Integer, String> seededValueTypes() throws IOException {
        Pattern insert = Pattern.compile("\\((\\d+),\\s*'([A-Z0-9]+)'");
        Pattern rename = Pattern.compile(
                "UPDATE\\s+timeseries_value_type\\s+SET\\s+name\\s*=\\s*'([A-Z0-9]+)'\\s+WHERE\\s+id\\s*=\\s*(\\d+)",
                Pattern.CASE_INSENSITIVE);
        Map<Integer, String> rows = new HashMap<>();
        for (Path script : migrationsInVersionOrder()) {
            String sql = Files.readString(script);
            if (!sql.contains("timeseries_value_type")) {
                continue;
            }
            for (String statement : sql.split(";")) {
                if (!statement.contains("timeseries_value_type")) {
                    continue;
                }
                if (statement.toUpperCase().contains("INSERT INTO")) {
                    Matcher m = insert.matcher(statement);
                    while (m.find()) {
                        rows.put(Integer.parseInt(m.group(1)), m.group(2));
                    }
                }
                Matcher renamed = rename.matcher(statement);
                while (renamed.find()) {
                    rows.put(Integer.parseInt(renamed.group(2)), renamed.group(1));
                }
            }
        }
        assertTrue(rows.size() >= 7, "parsed only " + rows + " from the migrations; has their shape changed?");
        return new TreeMap<>(rows);
    }

    private static List<Path> migrationsInVersionOrder() throws IOException {
        assertTrue(Files.isDirectory(MIGRATIONS), "no migration directory at " + MIGRATIONS.toAbsolutePath());
        try (Stream<Path> scripts = Files.list(MIGRATIONS)) {
            return scripts.filter(p -> p.getFileName().toString().endsWith(".sql"))
                    .sorted(java.util.Comparator.comparingInt(DatapointValueTypeParityTest::version))
                    .toList();
        }
    }

    private static int version(Path script) {
        Matcher m = Pattern.compile("^V(\\d+)").matcher(script.getFileName().toString());
        return m.find() ? Integer.parseInt(m.group(1)) : Integer.MAX_VALUE;
    }
}
