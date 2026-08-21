// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.scheduled;

import ai.intellistream.datahub.clickhouse.ClickHouseEventService;
import ai.intellistream.datahub.repositories.event.EventDimensionRepository;
import ai.intellistream.datahub.repositories.event.EventDimensionRepository.Dimension;
import ai.intellistream.datahub.tenant.TenantConfigService;
import ai.intellistream.datahub.tenant.TenantContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.Map;

/**
 * Weekly reconciliation of the event type / sub_type / status / source dimension tables (Flyway V29
 * and V30) against their ClickHouse source of truth.
 *
 * <p>The write path (in the stateless consumer) only ever <em>adds</em> values to those tables
 * ({@code INSERT ... ON CONFLICT DO NOTHING}); it cannot know when the last event of a given
 * (data_set_id, value) was deleted or had its category changed. This job recomputes the authoritative
 * DISTINCT set from {@code events} and {@link EventDimensionRepository#rebuildAll rebuilds} all four
 * tables in one statement, dropping values that no longer occur.
 *
 * <p>It lives in the stateful consumer because that module runs as a Pulsar <em>Failover</em>
 * (single-active) deployment rather than scaling out — so the cross-tenant rebuild runs on one
 * instance without needing a distributed lock — and it already has the beans this needs: a ClickHouse
 * client (per-tenant), a Postgres datasource, and {@link TenantConfigService}. Enabled by default;
 * set {@code datahub.event-dimension.reconcile.enabled=false} to disable (e.g. on standby replicas if
 * you run more than one for HA).
 */
@Slf4j
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "datahub.event-dimension.reconcile.enabled", havingValue = "true", matchIfMissing = true)
public class EventDimensionReconciliationTask {

    private final ClickHouseEventService clickHouseEventService;
    private final EventDimensionRepository eventDimensionRepository;
    private final TenantConfigService tenantConfigService;
    private final Environment environment;

    public EventDimensionReconciliationTask(ClickHouseEventService clickHouseEventService,
                                            EventDimensionRepository eventDimensionRepository,
                                            TenantConfigService tenantConfigService,
                                            Environment environment) {
        this.clickHouseEventService = clickHouseEventService;
        this.eventDimensionRepository = eventDimensionRepository;
        this.tenantConfigService = tenantConfigService;
        this.environment = environment;
    }

    /**
     * In local development the dimension tables start empty (nothing has flowed through the write path
     * yet), which makes the console's type/sub_type/status/source dropdowns look broken. When the
     * {@code dev} profile is active, run one reconciliation on startup so the tables are populated from
     * ClickHouse immediately. Other profiles wait for the scheduled weekly run.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void reconcileOnDevStartup() {
        if (!environment.matchesProfiles("dev")) {
            return;
        }
        log.info("dev profile active — running event dimension reconciliation once on startup");
        reconcile();
    }

    // Default: 03:00 every Sunday. Override with datahub.event-dimension.reconcile.cron.
    @Scheduled(cron = "${datahub.event-dimension.reconcile.cron:0 0 3 * * SUN}")
    public void reconcile() {
        var tenantIds = tenantConfigService.cachedTenants.keySet();
        log.info("Starting event dimension reconciliation for {} tenant(s)", tenantIds.size());
        for (String tenantId : tenantIds) {
            try {
                // runWith sets the tenant for both the ClickHouse client pool and the JPA routing
                // datasource, and clears it afterward — even if reconcileCurrentTenant throws.
                TenantContext.runWith(tenantId, this::reconcileCurrentTenant);
            } catch (Exception e) {
                // One tenant's failure must not abort the rest.
                log.error("Event dimension reconciliation failed for tenant {}: {}", tenantId, e.getMessage(), e);
            }
        }
        log.info("Finished event dimension reconciliation");
    }

    private void reconcileCurrentTenant() {
        eventDimensionRepository.rebuildAll(Map.of(
                Dimension.TYPE, clickHouseEventService.distinctTypePairs(),
                Dimension.SUB_TYPE, clickHouseEventService.distinctSubTypePairs(),
                Dimension.STATUS, clickHouseEventService.distinctStatusPairs(),
                Dimension.SOURCE, clickHouseEventService.distinctSourcePairs()));
    }
}
