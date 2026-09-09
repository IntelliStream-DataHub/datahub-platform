// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.helpers.datetime;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.format.DateTimeParseException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The seconds-or-millis rule, and the line between the overloads that apply it and the ones that
 * must not.
 */
class DateTimeHandlerEpochTest {

    private static final long SECONDS = 1_718_627_696L;
    private static final long MILLIS = 1_718_627_696_000L;
    private static final Instant T = Instant.parse("2024-06-17T12:34:56Z");

    @Test
    void secondsScaleUpAndMillisPassThrough() {
        assertEquals(MILLIS, DateTimeHandler.epochToMillis(SECONDS));
        assertEquals(MILLIS, DateTimeHandler.epochToMillis(MILLIS));
    }

    @Test
    void theCeilingIsTheOnlyThingThatDecides() {
        assertEquals(999_999_999_999_000L, DateTimeHandler.epochToMillis(999_999_999_999L));
        assertEquals(1_000_000_000_000L, DateTimeHandler.epochToMillis(1_000_000_000_000L));
    }

    /** An 11-digit epoch is under the ceiling, so it scales like any other seconds value. */
    @Test
    void anElevenDigitEpochStillScalesUp() {
        assertEquals(17_889_441_699_000L, DateTimeHandler.epochToMillis(17_889_441_699L));
    }

    @Test
    void negativeEpochsSplitSymmetrically() {
        assertEquals(-1_000_000_000_000L, DateTimeHandler.epochToMillis(-1_000_000_000L));
        assertEquals(-2_000_000_000_000L, DateTimeHandler.epochToMillis(-2_000_000_000_000L));
    }

    @Test
    void zeroStaysTheEpochItself() {
        assertEquals(0L, DateTimeHandler.epochToMillis(0L));
    }

    /** Every String overload takes values off the wire, so all three apply the rule. */
    @Test
    void stringOverloadsApplyTheRule() {
        assertEquals(MILLIS, DateTimeHandler.toEpochUTCTime(String.valueOf(SECONDS)));
        assertEquals(MILLIS, DateTimeHandler.toEpochUTCTime(String.valueOf(MILLIS)));

        assertEquals(T, DateTimeHandler.fromEpochUTCTimeAsZonedDateTime(String.valueOf(SECONDS)).toInstant());
        assertEquals(T, DateTimeHandler.fromEpochUTCTimeAsZonedDateTime(String.valueOf(MILLIS)).toInstant());

        assertEquals(DateTimeHandler.fromEpochUTCTime(MILLIS),
                DateTimeHandler.fromEpochUTCTime(String.valueOf(SECONDS)));
    }

    /**
     * A short all-digit value is a mistake, not a timestamp: "2024" means a year to whoever typed
     * it, and reading it as an epoch would silently store 1970-01-01T00:33:44Z instead of failing.
     */
    @Test
    void shortNumbersAreNotEpochs() {
        assertThrows(DateTimeParseException.class, () -> DateTimeHandler.parseClientTimestamp("2024"));
        assertThrows(DateTimeParseException.class, () -> DateTimeHandler.parseClientTimestamp("0"));
    }

    /** Nine digits is seconds back to 1973, and every path takes it, not just datapoint ingest. */
    @Test
    void nineDigitSecondsAreAccepted() {
        assertEquals(Instant.ofEpochSecond(900_000_000L),
                DateTimeHandler.parseClientTimestamp("900000000").toInstant());
    }

    /** ISO still wins over the epoch branch on the String overloads. */
    @Test
    void isoStringsAreUntouched() {
        assertEquals(MILLIS, DateTimeHandler.toEpochUTCTime("2024-06-17T12:34:56Z"));
        assertEquals(T, DateTimeHandler.fromEpochUTCTimeAsZonedDateTime("2024-06-17T14:34:56+02:00").toInstant());
    }

    /**
     * The long overloads read values already stored as millis (the graph, ClickHouse, EventModel's
     * own field). A small one is a genuine 1970 timestamp there, not seconds, so the rule must stay
     * off this path.
     */
    @Test
    void longOverloadsStayMillisOnly() {
        assertEquals(Instant.ofEpochMilli(SECONDS),
                DateTimeHandler.fromEpochUTCTimeAsZonedDateTime(SECONDS).toInstant());
        assertEquals(Instant.ofEpochMilli(SECONDS),
                DateTimeHandler.fromEpochUTCTime(SECONDS).toInstant(java.time.ZoneOffset.UTC));
    }
}
