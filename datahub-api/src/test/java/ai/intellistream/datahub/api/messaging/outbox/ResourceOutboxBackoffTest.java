// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.messaging.outbox;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The retry curve on its own. Collaborators are null because {@code backoffFor} touches none of
 * them — the same shape {@code TenantMigrationSweepTest} uses for the sweep's backoff.
 */
class ResourceOutboxBackoffTest {

    private final ResourceOutboxDrainService service = new ResourceOutboxDrainService(
            null, null, null, null, 1, Duration.ofSeconds(5), Duration.ofMinutes(10));

    @Test
    void firstAttemptWaitsTheBaseDelay() {
        assertThat(service.backoffFor(1)).isEqualTo(Duration.ofSeconds(5));
    }

    @Test
    void eachFailureDoublesTheWait() {
        assertThat(service.backoffFor(2)).isEqualTo(Duration.ofSeconds(10));
        assertThat(service.backoffFor(3)).isEqualTo(Duration.ofSeconds(20));
        assertThat(service.backoffFor(4)).isEqualTo(Duration.ofSeconds(40));
    }

    @Test
    void theWaitStopsGrowingAtTheCap() {
        assertThat(service.backoffFor(20)).isEqualTo(Duration.ofMinutes(10));
    }

    @Test
    void aLongStuckRowSaturatesRatherThanOverflowing() {
        // There is no attempt ceiling, so this counter really can grow without bound; doubling it
        // into an overflow would hand the row a backoff in the past and spin the drain.
        assertThat(service.backoffFor(Integer.MAX_VALUE)).isEqualTo(Duration.ofMinutes(10));
    }
}
