// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.clickhouse;

import org.junit.jupiter.api.Test;

import java.time.ZonedDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins {@link ClickHouseService#toChDateTime} to UTC normalisation. The underlying formatter has no offset
 * field, so before this helper existed a zoned bind parameter was formatted as its own wall clock and the
 * offset was silently dropped — a filter from {@code 14:00+02:00} queried from 14:00 UTC rather than 12:00,
 * shifting the window by the caller's offset. Console callers always send {@code Z} and so never saw it;
 * these cases cover the direct REST/SDK/MCP callers that {@code TimestampDeserializer} deliberately supports.
 */
class ClickHouseServiceDateTimeTest {

    @Test
    void positiveOffsetIsConvertedToUtc() {
        assertEquals("2026-07-17 12:00:00.000",
                ClickHouseService.toChDateTime(ZonedDateTime.parse("2026-07-17T14:00:00+02:00")));
    }

    @Test
    void negativeOffsetIsConvertedToUtc() {
        assertEquals("2026-07-17 19:30:00.000",
                ClickHouseService.toChDateTime(ZonedDateTime.parse("2026-07-17T14:30:00-05:00")));
    }

    /** The console path: already UTC, so normalisation must be a no-op. Guards against regressing it. */
    @Test
    void zuluIsUnchanged() {
        assertEquals("2026-07-17 14:00:00.000",
                ClickHouseService.toChDateTime(ZonedDateTime.parse("2026-07-17T14:00:00Z")));
    }

    /** Region ids resolve their offset against the instant, so the summer-time rule applies here, not +01:00. */
    @Test
    void regionZoneUsesTheOffsetInEffectAtThatInstant() {
        assertEquals("2026-07-17 12:00:00.000",
                ClickHouseService.toChDateTime(ZonedDateTime.parse("2026-07-17T14:00:00+02:00[Europe/Oslo]")));
        assertEquals("2026-01-17 13:00:00.000",
                ClickHouseService.toChDateTime(ZonedDateTime.parse("2026-01-17T14:00:00+01:00[Europe/Oslo]")));
    }

    @Test
    void millisecondsSurviveTheConversion() {
        assertEquals("2026-07-17 12:00:00.123",
                ClickHouseService.toChDateTime(ZonedDateTime.parse("2026-07-17T14:00:00.123+02:00")));
    }

    /** Crossing a date boundary must move the date, not just the clock. */
    @Test
    void offsetCrossingMidnightMovesTheDate() {
        assertEquals("2026-07-16 22:15:00.000",
                ClickHouseService.toChDateTime(ZonedDateTime.parse("2026-07-17T00:15:00+02:00")));
    }
}
