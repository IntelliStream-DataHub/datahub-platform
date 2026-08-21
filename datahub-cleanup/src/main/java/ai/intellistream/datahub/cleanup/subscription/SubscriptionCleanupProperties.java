// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.cleanup.subscription;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Configuration for the orphaned-subscription sweep, bound from
 * {@code datahub.cleanup.subscription.*}. The {@code cron} schedule is read directly by the
 * {@code @Scheduled} annotation, so it is not bound here.
 */
@Data
@ConfigurationProperties(prefix = "datahub.cleanup.subscription")
public class SubscriptionCleanupProperties {

    /** Master switch for the sweep. */
    private boolean enabled = true;

    /** When true, log the candidates that <em>would</em> be deleted without deleting anything. */
    private boolean dryRun = true;

    /**
     * A candidate must have had no Pulsar consume/ack activity within this window (falling back to
     * the row's age only for a subscription that has never once been consumed). Guards both an
     * actively-consumed subscription whose client is briefly offline and a brand-new one whose
     * client simply hasn't connected yet.
     */
    private Duration staleAge = Duration.ofDays(7);

    /** A candidate's Pulsar subscription backlog must be at least this many messages. */
    private long minBacklogMessages = 1000;
}
