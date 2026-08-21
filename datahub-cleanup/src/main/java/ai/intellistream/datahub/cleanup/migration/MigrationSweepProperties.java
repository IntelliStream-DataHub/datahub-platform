// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.cleanup.migration;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Configuration for the tenant migration self-heal sweep, bound from
 * {@code datahub.cleanup.migration.*}. The {@code sweep-rate} is read directly by the
 * {@code @Scheduled} annotation, so it is not bound here.
 */
@Data
@ConfigurationProperties(prefix = "datahub.cleanup.migration")
public class MigrationSweepProperties {

    /** Master switch for the self-heal sweep and its boot pass. */
    private boolean enabled = true;

    /**
     * First retry delay after a tenant's migration fails, and the base of the per-tenant exponential
     * backoff: the n-th consecutive failure waits {@code baseBackoff * 2^(n-1)}, capped at
     * {@link #maxBackoff}. Keeps a permanently-broken tenant from being retried every sweep.
     */
    private Duration baseBackoff = Duration.ofMinutes(5);

    /** Ceiling on the per-tenant backoff, so a long-broken tenant is still retried this often. */
    private Duration maxBackoff = Duration.ofHours(6);
}
