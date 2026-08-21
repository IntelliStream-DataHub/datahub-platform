// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.responses.datapoints;

import org.junit.jupiter.api.Test;

import static ai.intellistream.datahub.responses.datapoints.Granularity.createCHGranularity;
import static ai.intellistream.datahub.responses.datapoints.Granularity.createPostgresGranularity;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GranularityTest {

    @Test
    void testCreatePostgresGranularity() {

        String g1 = "1d";
        String g2 = "30d";
        String g3 = "120s";

        String r1 = createPostgresGranularity(g1);
        String r2 = createPostgresGranularity(g2);
        String r3 = createPostgresGranularity(g3);

        assertEquals("1 day", r1);
        assertEquals("30 day", r2);
        assertEquals("120 second", r3);
    }

    @Test
    void testFullWordUnits() {
        // The chart sends space-separated, full-word granularities.
        assertEquals("1 second", createCHGranularity("1 sec"));
        assertEquals("30 minute", createCHGranularity("30 min"));
        assertEquals("12 hour", createCHGranularity("12 hour"));
        assertEquals("1 day", createCHGranularity("1 day"));
    }

    @Test
    void testWeekMonthYearAreSupported() {
        // Regression: "1 week" used to be rejected (single-letter unit had no 'w' case).
        assertEquals("1 week", createCHGranularity("1 week"));
        assertEquals("1 month", createCHGranularity("1 month"));
        assertEquals("1 year", createCHGranularity("1 year"));
        assertEquals("1 week", createPostgresGranularity("1 week"));
        assertEquals("1 month", createPostgresGranularity("1 month"));
        assertEquals("1 year", createPostgresGranularity("1 year"));
    }

    @Test
    void testMonthDoesNotCollideWithMinute() {
        // Both start with 'm'; parsing the full word keeps them distinct.
        assertEquals("1 minute", createCHGranularity("1 min"));
        assertEquals("1 month", createCHGranularity("1 month"));
    }

    @Test
    void testInvalidGranularityThrows() {
        assertThrows(RuntimeException.class, () -> createCHGranularity("abc"));
        assertThrows(RuntimeException.class, () -> createCHGranularity("10"));
    }
}
