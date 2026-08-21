// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.jpa.domains;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Covers the hardened value-type resolution: a null or unrecognised type must not NPE or resolve
 * to the invalid id 0 (which mapped to the bigint table) — both fall back to FLOAT32. Known
 * values still resolve case-insensitively.
 */
class TimeseriesValueTypeTest {

    @Test
    void getValueTypeId_null_defaultsToFloat32() {
        assertEquals(TimeseriesValueType.FLOAT32, TimeseriesValueType.getValueTypeId(null));
    }

    @Test
    void getValueTypeId_unknownOrBlank_defaultsToFloat32() {
        assertEquals(TimeseriesValueType.FLOAT32, TimeseriesValueType.getValueTypeId("flot"));
        assertEquals(TimeseriesValueType.FLOAT32, TimeseriesValueType.getValueTypeId(""));
        assertEquals(TimeseriesValueType.FLOAT32, TimeseriesValueType.getValueTypeId("   "));
    }

    @Test
    void getValueTypeId_knownValues_areCaseInsensitive() {
        assertEquals(TimeseriesValueType.BIGINT, TimeseriesValueType.getValueTypeId("bigint"));
        assertEquals(TimeseriesValueType.FLOAT32, TimeseriesValueType.getValueTypeId("Float32"));
        assertEquals(TimeseriesValueType.NUMERIC, TimeseriesValueType.getValueTypeId("NUMERIC"));
        assertEquals(TimeseriesValueType.TEXT, TimeseriesValueType.getValueTypeId("text"));
        assertEquals(TimeseriesValueType.MIXED, TimeseriesValueType.getValueTypeId("mixed"));
    }

    @Test
    void getTableType_unknown_resolvesToFloat32TableNotBigint() {
        assertEquals("float32", TimeseriesValueType.getTableType("flot"));
    }
}
