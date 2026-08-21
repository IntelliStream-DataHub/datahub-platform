// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.services;

import ai.intellistream.datahub.services.ValkeyService;
import ai.intellistream.datahub.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.function.LongSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Exercises {@link LiveIngestCounter} generically (constructed the same way the "datapoints" bean
 * is in {@code LiveIngestCounterConfig}) — the logic is identical regardless of which metric it's
 * counting, so there's no need to duplicate this suite per metric.
 */
@ExtendWith(MockitoExtension.class)
class LiveIngestCounterTest {

    private static final String KEY = "dh:live:datapoints:tenant-a";

    @Mock private ValkeyService valkeyService;
    private LongSupplier trueCount;
    private LiveIngestCounter counter;

    @BeforeEach
    void setUp() {
        trueCount = mock(LongSupplier.class);
        counter = new LiveIngestCounter("dh:live:datapoints:", "datapoints", valkeyService, trueCount);
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void flushSkipsTenantsWithNothingPending() {
        counter.flush();
        verifyNoInteractions(valkeyService);
    }

    @Test
    void flushIncrementsByTheAccumulatedDeltaAndResetsIt() {
        when(valkeyService.getString(KEY)).thenReturn("500");
        when(trueCount.getAsLong()).thenReturn(500L); // matches -> no drift reset

        counter.recordIngested("tenant-a", 5);
        counter.recordIngested("tenant-a", 3);

        counter.flush();

        verify(valkeyService).increment(KEY, 8);

        // Nothing new accumulated since the last flush -> no further Valkey call, and the tenant is
        // already known-seeded so no repeat ClickHouse check either.
        counter.flush();
        verify(valkeyService, never()).increment(eq(KEY), eq(0L));
        verify(trueCount, times(1)).getAsLong();
    }

    @Test
    void flushSeedsFromClickHouseBeforeItsFirstIncrementIfNeverRead() {
        // An item ingested (and flushed) before the dashboard has ever been opened for this tenant
        // must not silently INCRBY a nonexistent key and start the counter at 0.
        when(valkeyService.getString(KEY)).thenReturn(null);
        when(trueCount.getAsLong()).thenReturn(1000L);
        when(valkeyService.setIfAbsent(KEY, "1000")).thenReturn(true);

        counter.recordIngested("tenant-a", 5);
        counter.flush();

        verify(valkeyService).setIfAbsent(KEY, "1000");
        verify(valkeyService).increment(KEY, 5);
    }

    @Test
    void flushResyncsBeforeItsFirstIncrementIfTheExistingValueHasDrifted() {
        // Regression test: a Valkey key left over from before this class validated on first touch
        // (e.g. created by a bare INCRBY pre-fix) must not be trusted forever just because it exists
        // — it needs to be caught and corrected the first time this instance touches it, not only on
        // the hourly reconcile.
        when(valkeyService.getString(KEY)).thenReturn("1500");
        when(trueCount.getAsLong()).thenReturn(500_000L);

        counter.recordIngested("tenant-a", 5);
        counter.flush();

        verify(valkeyService).put(Map.of(KEY, "500000"));
        verify(valkeyService).increment(KEY, 5);
    }

    @Test
    void getCurrentTotalReadsThroughWhenAlreadySeededAndWithinThreshold() {
        TenantContext.setTenantId("tenant-a");
        when(valkeyService.getString(KEY)).thenReturn("970");
        when(trueCount.getAsLong()).thenReturn(1000L); // 3% off -> within 5%, kept as-is

        assertEquals(970L, counter.getCurrentTotal());
        verify(valkeyService, never()).put(Map.of(KEY, "1000"));

        // A second call for the same tenant on the same instance shouldn't re-check ClickHouse.
        counter.getCurrentTotal();
        verify(trueCount, times(1)).getAsLong();
    }

    @Test
    void getCurrentTotalResyncsOnFirstTouchWhenTheExistingValueHasDrifted() {
        // The exact bug reported: a restarted instance (seededTenants reset, but the Valkey key
        // survived) must resync to ClickHouse immediately rather than trusting a stale low number
        // until the next hourly reconcile.
        TenantContext.setTenantId("tenant-a");
        when(valkeyService.getString(KEY)).thenReturn("1500");
        when(trueCount.getAsLong()).thenReturn(500_000L);

        assertEquals(500_000L, counter.getCurrentTotal());

        verify(valkeyService).put(Map.of(KEY, "500000"));
    }

    @Test
    void getCurrentTotalSeedsFromClickHouseOnFirstRead() {
        TenantContext.setTenantId("tenant-a");
        when(valkeyService.getString(KEY)).thenReturn(null);
        when(trueCount.getAsLong()).thenReturn(1234L);
        when(valkeyService.setIfAbsent(KEY, "1234")).thenReturn(true);

        assertEquals(1234L, counter.getCurrentTotal());
    }

    @Test
    void getCurrentTotalRereadsWhenItLosesTheSeedRace() {
        // Another instance seeded the key between our getString check and our setIfAbsent call.
        TenantContext.setTenantId("tenant-a");
        when(valkeyService.getString(KEY)).thenReturn(null, "999");
        when(trueCount.getAsLong()).thenReturn(1234L);
        when(valkeyService.setIfAbsent(KEY, "1234")).thenReturn(false);

        assertEquals(999L, counter.getCurrentTotal());
    }

    @Test
    void reconcileLeavesTheCounterAloneWhenWithinThreshold() {
        TenantContext.setTenantId("tenant-a");
        when(valkeyService.getString(KEY)).thenReturn("970"); // never changes -> nothing to write either time
        when(trueCount.getAsLong()).thenReturn(1000L); // 3% off -> within 5%

        counter.getCurrentTotal();  // first touch already checks for drift and finds none
        counter.reconcile();        // hourly check, still none

        verify(valkeyService, never()).put(Map.of(KEY, "1000"));
    }

    @Test
    void reconcileResyncsToClickHouseWhenDriftExceedsThreshold() {
        TenantContext.setTenantId("tenant-a");
        // First touch (via getCurrentTotal) sees the stale value and resyncs it; reconcile's own
        // check afterward sees the now-corrected value and doesn't resync a second time.
        when(valkeyService.getString(KEY)).thenReturn("800", "1000");
        when(trueCount.getAsLong()).thenReturn(1000L); // 20% off -> beyond 5%

        counter.getCurrentTotal();
        counter.reconcile();

        verify(valkeyService, times(1)).put(Map.of(KEY, "1000"));
    }
}
