// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.timeseries;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Covers the value-type default. An unspecified (omitted) or explicitly null valueType falls back
 * to {@link Timeseries#DEFAULT_VALUE_TYPE} (float32) instead of NPE-ing or being rejected; an
 * explicit value is normalised to lower case.
 */
class TimeseriesTest {

    @Test
    void valueType_defaultsToFloat32_whenOmitted() {
        assertEquals(Timeseries.DEFAULT_VALUE_TYPE, new Timeseries().getValueType());
        assertEquals("float32", new Timeseries().getValueType());
    }

    @Test
    void valueType_nullFallsBackToDefault() {
        Timeseries ts = new Timeseries();
        ts.setValueType(null);
        assertEquals("float32", ts.getValueType());
    }

    @Test
    void valueType_explicitValueIsLowercased() {
        Timeseries ts = new Timeseries();
        ts.setValueType("FLOAT");
        assertEquals("float", ts.getValueType());
    }
}
