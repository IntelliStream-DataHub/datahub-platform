// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.clickhouse;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static ai.intellistream.datahub.jpa.domains.TimeseriesValueType.BIGINT;
import static ai.intellistream.datahub.jpa.domains.TimeseriesValueType.DECIMAL32;
import static ai.intellistream.datahub.jpa.domains.TimeseriesValueType.FLOAT;
import static ai.intellistream.datahub.jpa.domains.TimeseriesValueType.FLOAT32;
import static ai.intellistream.datahub.jpa.domains.TimeseriesValueType.MIXED;
import static ai.intellistream.datahub.jpa.domains.TimeseriesValueType.NUMERIC;
import static ai.intellistream.datahub.jpa.domains.TimeseriesValueType.TEXT;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pure round-trip checks for the binary value codec (no ClickHouse needed). The companion
 * Testcontainers test proves the same bytes are accepted by a real ClickHouse server.
 */
class DatapointValueCodecTest {

    private static String roundTrip(String value, int typeId) {
        return DatapointValueCodec.decodeToString(DatapointValueCodec.encode(value, typeId), typeId);
    }

    private static void assertNumericRoundTrip(String value, int typeId) {
        assertEquals(0, new BigDecimal(value).compareTo(new BigDecimal(roundTrip(value, typeId))),
                "numeric round-trip mismatch for type " + typeId + " value " + value);
    }

    @Test
    void bigintRoundTrips() {
        assertNumericRoundTrip("0", BIGINT);
        assertNumericRoundTrip("12345", BIGINT);
        assertNumericRoundTrip("-9223372036854775808", BIGINT);
        assertNumericRoundTrip("9223372036854775807", BIGINT);
    }

    @Test
    void floatRoundTrips() {
        assertEquals(3.141592653589793, Double.parseDouble(roundTrip("3.141592653589793", FLOAT)), 0.0);
        assertEquals(-0.5, Double.parseDouble(roundTrip("-0.5", FLOAT)), 0.0);
    }

    @Test
    void float32RoundTrips() {
        // exactly-representable values round-trip exactly
        assertEquals(3.5f, Float.parseFloat(roundTrip("3.5", FLOAT32)), 0.0f);
        assertEquals(-0.5f, Float.parseFloat(roundTrip("-0.5", FLOAT32)), 0.0f);
        // values needing more than ~7 significant digits are narrowed to the nearest 32-bit float
        assertEquals(3.1415927f, Float.parseFloat(roundTrip("3.141592653589793", FLOAT32)), 0.0f);
    }

    @Test
    void numericRoundTripsExactTo6Decimals() {
        assertNumericRoundTrip("344.544", NUMERIC);
        assertNumericRoundTrip("-1234.5678", NUMERIC);
        assertNumericRoundTrip("0.000001", NUMERIC);
    }

    @Test
    void decimal32RoundsTo4DecimalsAndClampsMagnitude() {
        // exact within 4 decimals
        assertNumericRoundTrip("344.544", DECIMAL32);
        // 5th+ decimal is rounded half-up
        assertEquals(0, new BigDecimal("1.2346").compareTo(new BigDecimal(roundTrip("1.23456", DECIMAL32))));
        // out-of-range magnitudes clamp to the rail rather than throwing
        assertEquals(0, new BigDecimal("99999.9999").compareTo(new BigDecimal(roundTrip("250000.5", DECIMAL32))));
        assertEquals(0, new BigDecimal("-99999.9999").compareTo(new BigDecimal(roundTrip("-1000000", DECIMAL32))));
    }

    @Test
    void textRoundTrips() {
        assertEquals("FAULT", roundTrip("FAULT", TEXT));
        assertEquals("", roundTrip("", TEXT));
        assertEquals("unit: °C / status=OK", roundTrip("unit: °C / status=OK", TEXT));
    }

    @Test
    void mixedRoundTripsBothNumericAndText() {
        assertEquals(23.5, Double.parseDouble(roundTrip("23.5", MIXED)), 0.0);
        assertEquals("FAULT", roundTrip("FAULT", MIXED));
        assertEquals("N/A", roundTrip("N/A", MIXED));
    }
}
