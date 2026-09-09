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

    @Test
    void tenDigitEpochIsSeconds() {
        assertEquals(T, min("1718627696"));
    }

    /** Both forms quoted: the deserializer reads the token as text, so the unit rule is the same. */
    @Test
    void quotedEpochsReadTheSameAsBareOnes() {
        assertEquals(T, min("\"1718627696000\""));
        assertEquals(T, min("\"1718627696\""));
    }

    @Test
    void isoKeepsItsInstantWhateverTheOffset() {
        assertEquals(T, min("\"2024-06-17T12:34:56Z\""));
        assertEquals(T, min("\"2024-06-17T14:34:56+02:00\""));
    }

    /**
     * The seconds/millis split. Below the ceiling is seconds, which as milliseconds would be a
     * 1970 date no caller means; at the ceiling and above is milliseconds.
     */
    @Test
    void theCeilingDecidesTheUnit() {
        assertEquals(Instant.ofEpochSecond(9_999_999_999L), min("9999999999"));
        assertEquals(Instant.ofEpochMilli(10_000_000_000L), min("10000000000"));
    }

    /** A negative epoch is pre-1970 and splits on magnitude the same way. */
    @Test
    void negativeEpochsAreAccepted() {
        assertEquals(Instant.ofEpochSecond(-1_000_000_000L), min("-1000000000"));
        assertEquals(Instant.ofEpochMilli(-100_000_000_000L), min("-100000000000"));
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
