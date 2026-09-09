// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.helpers.datetime;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * One instant, written four ways. An ISO-8601 string carries its own offset, so dropping it
 * silently shifts a timestamp by hours, which no later code can detect or undo.
 */
class DateTimeHandlerZoneTest {

    private static final Instant T = Instant.parse("2024-06-17T12:34:56Z");

    private static final String ZULU = "2024-06-17T12:34:56Z";
    private static final String OSLO = "2024-06-17T14:34:56+02:00";
    private static final String NEW_YORK = "2024-06-17T08:34:56-04:00";
    private static final String REGION = "2024-06-17T14:34:56+02:00[Europe/Oslo]";

    @Test
    void toEpochUTCTimeHonoursTheOffset() {
        assertEquals(T.toEpochMilli(), DateTimeHandler.toEpochUTCTime(ZULU));
        assertEquals(T.toEpochMilli(), DateTimeHandler.toEpochUTCTime(OSLO));
        assertEquals(T.toEpochMilli(), DateTimeHandler.toEpochUTCTime(NEW_YORK));
        assertEquals(T.toEpochMilli(), DateTimeHandler.toEpochUTCTime(REGION));
    }

    @Test
    void fromEpochUTCTimeAsZonedDateTimeHonoursTheOffset() {
        assertEquals(T, DateTimeHandler.fromEpochUTCTimeAsZonedDateTime(ZULU).toInstant());
        assertEquals(T, DateTimeHandler.fromEpochUTCTimeAsZonedDateTime(OSLO).toInstant());
        assertEquals(T, DateTimeHandler.fromEpochUTCTimeAsZonedDateTime(NEW_YORK).toInstant());
        assertEquals(T, DateTimeHandler.fromEpochUTCTimeAsZonedDateTime(REGION).toInstant());
    }

    /**
     * The shared parse hands back what the caller sent, offset and region and all. Only the
     * {@code ...AsZonedDateTime} wrapper moves it to UTC, and it moves the instant with it.
     */
    @Test
    void parseClientTimestampKeepsTheCallersZone() {
        assertEquals(ZoneOffset.ofHours(2), DateTimeHandler.parseClientTimestamp(OSLO).getOffset());
        assertEquals(ZoneOffset.ofHours(-4), DateTimeHandler.parseClientTimestamp(NEW_YORK).getOffset());
        assertEquals(ZoneId.of("Europe/Oslo"), DateTimeHandler.parseClientTimestamp(REGION).getZone());

        assertEquals(T, DateTimeHandler.parseClientTimestamp(OSLO).toInstant());
        assertEquals(ZoneOffset.UTC, DateTimeHandler.fromEpochUTCTimeAsZonedDateTime(OSLO).getOffset());
    }

    /** An epoch has no zone of its own, so it is UTC by definition. */
    @Test
    void anEpochParsesAsUtc() {
        assertEquals(ZoneOffset.UTC, DateTimeHandler.parseClientTimestamp("1718627696").getOffset());
        assertEquals(T, DateTimeHandler.parseClientTimestamp("1718627696").toInstant());
    }

    /** This one returns a LocalDateTime, and the contract says that local time is UTC. */
    @Test
    void fromEpochUTCTimeHonoursTheOffset() {
        LocalDateTime expected = LocalDateTime.ofInstant(T, ZoneOffset.UTC);

        assertEquals(expected, DateTimeHandler.fromEpochUTCTime(ZULU));
        assertEquals(expected, DateTimeHandler.fromEpochUTCTime(OSLO));
        assertEquals(expected, DateTimeHandler.fromEpochUTCTime(NEW_YORK));
    }
}
