// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.cleanup.migration;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Covers {@link TenantMigrationSweep#backoffFor}: the per-tenant retry delay must double with each
 * consecutive failure and saturate at {@code maxBackoff} — so a permanently-broken tenant is not
 * retried every sweep, yet a long-broken one is still retried at least once per {@code maxBackoff}.
 */
class TenantMigrationSweepTest {

    private TenantMigrationSweep sweepWith(Duration base, Duration max) {
        MigrationSweepProperties props = new MigrationSweepProperties();
        props.setBaseBackoff(base);
        props.setMaxBackoff(max);
        return new TenantMigrationSweep(null, null, props);
    }

    @Test
    void doublesEachFailureThenCapsAtMax() {
        TenantMigrationSweep sweep = sweepWith(Duration.ofMinutes(5), Duration.ofHours(6));

        assertEquals(Duration.ofMinutes(5), sweep.backoffFor(1));   // 5m * 2^0
        assertEquals(Duration.ofMinutes(10), sweep.backoffFor(2));  // 5m * 2^1
        assertEquals(Duration.ofMinutes(20), sweep.backoffFor(3));  // 5m * 2^2
        assertEquals(Duration.ofMinutes(40), sweep.backoffFor(4));  // 5m * 2^3
        assertEquals(Duration.ofMinutes(80), sweep.backoffFor(5));  // 5m * 2^4
        assertEquals(Duration.ofMinutes(160), sweep.backoffFor(6)); // 5m * 2^5 = 2h40m, still < 6h
        assertEquals(Duration.ofMinutes(320), sweep.backoffFor(7)); // 5m * 2^6 = 5h20m, still < 6h
        assertEquals(Duration.ofHours(6), sweep.backoffFor(8));     // 5m * 2^7 = 10h40m, capped at 6h
        assertEquals(Duration.ofHours(6), sweep.backoffFor(9));     // capped
    }

    @Test
    void neverExceedsMaxEvenForHugeFailureCounts() {
        TenantMigrationSweep sweep = sweepWith(Duration.ofMinutes(5), Duration.ofHours(6));
        // A large count must saturate at the cap, not overflow.
        assertEquals(Duration.ofHours(6), sweep.backoffFor(1000));
        assertEquals(Duration.ofHours(6), sweep.backoffFor(Integer.MAX_VALUE));
    }

    @Test
    void capsOnFirstFailureWhenBaseAlreadyExceedsMax() {
        TenantMigrationSweep sweep = sweepWith(Duration.ofHours(10), Duration.ofHours(6));
        assertEquals(Duration.ofHours(6), sweep.backoffFor(1));
    }
}
