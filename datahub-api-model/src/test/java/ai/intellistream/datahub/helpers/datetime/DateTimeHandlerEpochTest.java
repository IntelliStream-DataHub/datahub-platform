// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.helpers.datetime;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The epoch half of the wire contract: milliseconds, within a width, and a refusal that explains
 * itself for everything else.
 *
 * <p>The unit is not inferred. A rule that reads magnitude has a band where it reads wrong and
 * reports nothing — a value that is really milliseconds comes back as a date thousands of years
 * out, or one that is really seconds comes back as 1970, and either way the caller is told the
 * request succeeded. Refusing costs the caller one round trip and tells them the fix.
 */
class DateTimeHandlerEpochTest {

    private static final long MILLIS = 1_718_627_696_000L;
    private static final Instant T = Instant.parse("2024-06-17T12:34:56Z");

    @Test
    void anEpochIsMilliseconds() {
        assertEquals(T, DateTimeHandler.parseClientTimestamp(String.valueOf(MILLIS)).toInstant());
        assertEquals(MILLIS, DateTimeHandler.toEpochUTCTime(String.valueOf(MILLIS)));
        assertEquals(T, DateTimeHandler.fromEpochUTCTimeAsZonedDateTime(String.valueOf(MILLIS)).toInstant());
        assertEquals(DateTimeHandler.fromEpochUTCTime(MILLIS),
                DateTimeHandler.fromEpochUTCTime(String.valueOf(MILLIS)));
    }

    /**
     * The band, at both edges: twelve digits reaches back to 1973-03-03 and fourteen forward to the
     * year 5138, which covers everything a caller sends as a number.
     */
    @Test
    void theAcceptedWidthRunsFromTwelveToFourteenDigits() {
        assertEquals(Instant.parse("1973-03-03T09:46:40Z"),
                DateTimeHandler.parseClientTimestamp("100000000000").toInstant());
        assertEquals(Instant.ofEpochMilli(99_999_999_999_999L),
                DateTimeHandler.parseClientTimestamp("99999999999999").toInstant());
    }

    /**
     * Ten digits is what a caller sending the wrong unit types, so it is named rather than read.
     * Silently scaling it is the whole bug this contract exists to prevent: the value parses, the
     * request succeeds, and the event is filed some 56 000 years out.
     */
    @Test
    void tenDigitSecondsAreRefusedAndTheMessageSaysWhy() {
        DateTimeParseException e = assertThrows(DateTimeParseException.class,
                () -> DateTimeHandler.parseClientTimestamp("1718627696"));

        assertTrue(e.getMessage().contains("epoch seconds"), e.getMessage());
        assertTrue(e.getMessage().contains("multiply it by 1000"), e.getMessage());
    }

    /** Nine and eleven digits are seconds-shaped too, and get the same explanation. */
    @Test
    void theSecondsHintCoversTheWholeSecondsShapedBand() {
        assertTrue(assertThrows(DateTimeParseException.class,
                () -> DateTimeHandler.parseClientTimestamp("900000000")).getMessage()
                .contains("epoch seconds"));
        assertTrue(assertThrows(DateTimeParseException.class,
                () -> DateTimeHandler.parseClientTimestamp("17889441699")).getMessage()
                .contains("epoch seconds"));
    }

    /**
     * A short all-digit value is a mistake, not a timestamp: "2024" means a year to whoever typed
     * it. It is not seconds-shaped either, so it gets the plain wording rather than a hint that
     * would send the caller off multiplying.
     */
    @Test
    void shortNumbersAreNotEpochs() {
        DateTimeParseException e = assertThrows(DateTimeParseException.class,
                () -> DateTimeHandler.parseClientTimestamp("2024"));

        assertTrue(e.getMessage().contains("not a valid timestamp"), e.getMessage());
        assertTrue(!e.getMessage().contains("epoch seconds"), e.getMessage());
        assertThrows(DateTimeParseException.class, () -> DateTimeHandler.parseClientTimestamp("0"));
    }

    /** Every refusal points at the way out, because the epoch band does not reach back far enough. */
    @Test
    void theMessageOffersIsoForAnythingOlder() {
        assertTrue(assertThrows(DateTimeParseException.class,
                () -> DateTimeHandler.parseClientTimestamp("99999999999")).getMessage()
                .contains("before 1973-03-03, use ISO-8601"));
    }

    /** A negative epoch is pre-1970 millis, and counts its digits the same way. */
    @Test
    void negativeEpochsAreMillisToo() {
        assertEquals(Instant.ofEpochMilli(-2_000_000_000_000L),
                DateTimeHandler.parseClientTimestamp("-2000000000000").toInstant());
        assertThrows(DateTimeParseException.class,
                () -> DateTimeHandler.parseClientTimestamp("-1000000000"));
    }

    /** ISO still wins over the epoch branch on the String overloads. */
    @Test
    void isoStringsAreUntouched() {
        assertEquals(MILLIS, DateTimeHandler.toEpochUTCTime("2024-06-17T12:34:56Z"));
        assertEquals(T, DateTimeHandler.fromEpochUTCTimeAsZonedDateTime("2024-06-17T14:34:56+02:00").toInstant());
    }

    /**
     * The long overloads take values already stored as millis (the graph, ClickHouse, EventModel's
     * own field) and are not a wire path, so the width check must stay off them: a small value
     * there is a genuine 1970 timestamp, not a caller's mistake to refuse.
     */
    @Test
    void longOverloadsAcceptAnyMillis() {
        assertEquals(Instant.ofEpochMilli(1_718_627_696L),
                DateTimeHandler.fromEpochUTCTimeAsZonedDateTime(1_718_627_696L).toInstant());
        assertEquals(Instant.ofEpochMilli(1_718_627_696L),
                DateTimeHandler.fromEpochUTCTime(1_718_627_696L).toInstant(ZoneOffset.UTC));
    }
}
