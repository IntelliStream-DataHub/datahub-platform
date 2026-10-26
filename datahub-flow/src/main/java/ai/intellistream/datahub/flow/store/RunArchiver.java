// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.flow.store;

import ai.intellistream.datahub.flow.store.RunStore.StoredRun;
import ai.intellistream.datahub.tenant.Tenant;
import ai.intellistream.datahub.tenant.TenantConfigService;
import ai.intellistream.datahub.tenant.TenantContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Moves finished runs from each tenant's run store into its ClickHouse history. Every instance runs
 * it; the store hands a batch to one of them at a time and hides it from the rest, and takes it
 * back if that one never reports it archived, so a run is archived by whoever gets there first and
 * is not lost to one that dies half way.
 *
 * <p>If a tenant's ClickHouse is down, or lacks the {@code flow_run} table, its finished runs wait
 * in the run store, where they stay readable, and are tried again when the batch reappears. Another
 * tenant's archive is not held up by it.
 */
@Slf4j
@Component
public class RunArchiver {

    private static final int BATCH = 500;
    /** How long a batch is the archiver's own; long enough for a slow insert, short enough to retry. */
    private static final Duration HIDE = Duration.ofSeconds(30);

    private final TenantConfigService tenants;
    private final RunStore store;
    private final RunHistory history;
    private final AgentTranscripts transcripts;

    public RunArchiver(TenantConfigService tenants, RunStore store, RunHistory history,
                       AgentTranscripts transcripts) {
        this.tenants = tenants;
        this.store = store;
        this.history = history;
        this.transcripts = transcripts;
    }

    @Scheduled(initialDelayString = "${datahub.flow.runs.archive-interval:PT1S}",
            fixedDelayString = "${datahub.flow.runs.archive-interval:PT1S}")
    public void archiveAll() {
        Map<String, Tenant> registry = tenants.cachedTenants;
        if (registry == null) {
            return;
        }
        for (Tenant tenant : registry.values()) {
            if (tenant.getFeatures() == null || !tenant.getFeatures().isFlowFeatureEnabled()) {
                continue;
            }
            String tenantId = tenant.getOrganizationId();
            try {
                TenantContext.runWith(tenantId, () -> archive(tenantId));
            } catch (RuntimeException e) {
                log.warn("Archiving the runs of tenant {} failed; trying again: {}", tenantId, e.getMessage());
            }
        }
    }

    /** Archives what is waiting for the current tenant; how many runs. */
    int archive(String tenantId) {
        int total = 0;
        List<StoredRun> batch;
        do {
            batch = store.takeUnarchived(BATCH, HIDE);
            if (batch.isEmpty()) {
                break;
            }
            try {
                history.insert(tenantId, batch);
                // An agent run's transcripts go with it, one list per attempt, before the run is
                // marked archived and its lists expire.
                for (StoredRun run : batch) {
                    for (int attempt = 1; attempt <= run.attempt(); attempt++) {
                        history.insertTranscript(tenantId, run.id(), attempt, transcripts.read(run.id(), attempt, -1));
                    }
                }
            } catch (Exception e) {
                log.warn("Writing {} finished runs of tenant {} to ClickHouse failed; they come back in {}: {}",
                        batch.size(), tenantId, HIDE, e.getMessage());
                return total;
            }
            store.archived(batch.stream().map(StoredRun::id).toList());
            total += batch.size();
        } while (batch.size() == BATCH);
        return total;
    }
}
