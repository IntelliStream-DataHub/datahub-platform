// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.clickhouse;

import ai.intellistream.datahub.api.responses.DataCollectionBin;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the window rules of {@link ClickHouseDatapointService#buildDeleteQuery}. The fully-open case
 * is what a timeseries delete emits to purge the data-points of a definition that no longer exists —
 * before that existed, {@code inclusiveBegin} was parsed unconditionally and a null bound blew up.
 */
class ClickHouseDatapointDeleteQueryTest {

    private static DataCollectionBin item(String begin, String end) {
        DataCollectionBin dc = new DataCollectionBin();
        dc.setId(42L);
        dc.setValueType("FLOAT");
        dc.setInclusiveBegin(begin);
        dc.setExclusiveEnd(end);
        return dc;
    }

    @Test
    void noBoundsPurgesTheWholeTimeseries() {
        var q = ClickHouseDatapointService.buildDeleteQuery(item(null, null));

        assertEquals("DELETE FROM datapoints_float WHERE timeseries_id = {timeseriesId:Int64}", q.sql());
        assertEquals(42L, q.params().get("timeseriesId"));
        assertEquals(1, q.params().size(), "no time bounds should be bound");
    }

    @Test
    void beginOnlyDeletesFromThatPointOnward() {
        var q = ClickHouseDatapointService.buildDeleteQuery(item("2026-01-01T00:00:00Z", null));

        assertTrue(q.sql().contains("timestamp >= {startTime:DateTime64(3)}"));
        assertFalse(q.sql().contains("endTime"));
        assertEquals("2026-01-01 00:00:00.000", q.params().get("startTime"));
    }

    @Test
    void endOnlyDeletesEverythingBeforeIt() {
        var q = ClickHouseDatapointService.buildDeleteQuery(item(null, "2026-01-01T00:00:00Z"));

        assertFalse(q.sql().contains("startTime"));
        assertTrue(q.sql().contains("timestamp < {endTime:DateTime64(3)}"));
        assertEquals("2026-01-01 00:00:00.000", q.params().get("endTime"));
    }

    /** Both bounds is the {@code /timeseries/data/delete} shape; offsets must still normalise to UTC. */
    @Test
    void bothBoundsAreNormalisedToUtc() {
        var q = ClickHouseDatapointService.buildDeleteQuery(
                item("2026-01-01T02:00:00+02:00", "2026-01-02T02:00:00+02:00"));

        assertEquals("DELETE FROM datapoints_float WHERE timeseries_id = {timeseriesId:Int64}"
                + " AND timestamp >= {startTime:DateTime64(3)}"
                + " AND timestamp < {endTime:DateTime64(3)}", q.sql());
        assertEquals("2026-01-01 00:00:00.000", q.params().get("startTime"));
        assertEquals("2026-01-02 00:00:00.000", q.params().get("endTime"));
    }

    /**
     * Epoch millis is a documented form for every timestamp on the API, but this window used to go
     * through a bare {@code ZonedDateTime.parse} — so an epoch bound blew up in the consumer, well
     * after the endpoint had already answered 204.
     */
    @Test
    void epochMillisBoundsAreAccepted() {
        var q = ClickHouseDatapointService.buildDeleteQuery(item("1767225600000", "1767312000000"));

        assertEquals("2026-01-01 00:00:00.000", q.params().get("startTime"));
        assertEquals("2026-01-02 00:00:00.000", q.params().get("endTime"));
    }

    /** Mixing the two forms in one window is fine — each bound is parsed on its own. */
    @Test
    void theTwoFormsCanBeMixedInOneWindow() {
        var q = ClickHouseDatapointService.buildDeleteQuery(item("2026-01-01T00:00:00Z", "1767312000000"));

        assertEquals("2026-01-01 00:00:00.000", q.params().get("startTime"));
        assertEquals("2026-01-02 00:00:00.000", q.params().get("endTime"));
    }

    /** The value type picks the table, so a purge must not hit the wrong one. */
    @Test
    void valueTypeSelectsTheTable() {
        DataCollectionBin dc = item(null, null);
        dc.setValueType("TEXT");

        assertTrue(ClickHouseDatapointService.buildDeleteQuery(dc).sql().startsWith("DELETE FROM datapoints_text "));
    }
}
