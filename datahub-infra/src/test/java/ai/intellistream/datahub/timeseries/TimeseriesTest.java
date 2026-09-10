// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.timeseries;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

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

    /**
     * Null is preserved as "the source did not supply one" rather than folded into the default.
     *
     * <p>It used to fall back, which meant no source could say it did not know — and the graph is
     * such a source: it stores no value type, so a series read through the graph reported
     * {@code float32} whatever it actually was. The default for a body that simply omits the
     * field is unaffected; that comes from the field initialiser, pinned by
     * {@link #valueType_defaultsToFloat32_whenOmitted}, and Jackson never calls a setter for an
     * absent property. An explicit {@code null} now reaches {@code @NotBlank} and is rejected with
     * a message, instead of being quietly replaced.
     */
    @Test
    void valueType_nullIsKeptAsUnsupplied() {
        Timeseries ts = new Timeseries();
        ts.setValueType(null);
        assertNull(ts.getValueType());
    }

    @Test
    void valueType_explicitValueIsLowercased() {
        Timeseries ts = new Timeseries();
        ts.setValueType("FLOAT");
        assertEquals("float", ts.getValueType());
    }
}
