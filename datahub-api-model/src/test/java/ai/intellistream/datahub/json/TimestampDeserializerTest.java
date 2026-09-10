// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.json;

import ai.intellistream.datahub.models.datafilters.TimeFilter;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.time.ZonedDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The one place a client-supplied timestamp becomes an instant. Driven through {@link TimeFilter},
 * a real consumer, so the annotation wiring is covered alongside the parsing.
 */
class TimestampDeserializerTest {

    private final JsonMapper mapper = JsonMapper.builder().build();

    private static final Instant T = Instant.parse("2024-06-17T12:34:56Z");

    private Instant min(String jsonValue) {
        ZonedDateTime min = mapper.readValue("{\"min\":" + jsonValue + "}", TimeFilter.class).getMin();
        return min == null ? null : min.toInstant();
    }

    @Test
    void thirteenDigitEpochIsMillis() {
        assertEquals(T, min("1718627696000"));
    }

    /** A quoted epoch is the same value as a bare one — the deserializer reads the token as text. */
    @Test
    void quotedEpochsReadTheSameAsBareOnes() {
        assertEquals(T, min("\"1718627696000\""));
    }

    /**
     * Seconds are refused rather than scaled, on the JSON path as everywhere else. The caller finds
     * out on the first request instead of from a window that quietly covered the wrong century.
     */
    @Test
    void tenDigitSecondsAreRefused() {
        assertThrows(RuntimeException.class, () -> min("1718627696"));
        assertThrows(RuntimeException.class, () -> min("\"1718627696\""));
    }

    @Test
    void isoKeepsItsInstantWhateverTheOffset() {
        assertEquals(T, min("\"2024-06-17T12:34:56Z\""));
        assertEquals(T, min("\"2024-06-17T14:34:56+02:00\""));
    }

    /** The accepted width, at both edges. Everything inside it is milliseconds. */
    @Test
    void theWidthDecidesWhetherItIsAnEpochAtAll() {
        assertEquals(Instant.ofEpochMilli(100_000_000_000L), min("100000000000"));
        assertEquals(Instant.ofEpochMilli(99_999_999_999_999L), min("99999999999999"));
        assertThrows(RuntimeException.class, () -> min("17889441699"));
        assertThrows(RuntimeException.class, () -> min("999999999999999"));
    }

    /** A negative epoch is pre-1970 millis, counted the same way. */
    @Test
    void negativeEpochsAreAccepted() {
        assertEquals(Instant.ofEpochMilli(-2_000_000_000_000L), min("-2000000000000"));
    }

    @Test
    void blankIsNullSoValidationCanReportTheMissingField() {
        assertNull(min("\"\""));
    }

    @Test
    void garbageIsRejectedRatherThanGuessed() {
        assertThrows(RuntimeException.class, () -> min("\"not-a-timestamp\""));
    }
}
