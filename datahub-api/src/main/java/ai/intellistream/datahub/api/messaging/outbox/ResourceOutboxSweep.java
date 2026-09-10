// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.messaging.outbox;

import ai.intellistream.datahub.repositories.outbox.ResourceOutboxRepository;
import ai.intellistream.datahub.tenant.TenantConfigService;
import ai.intellistream.datahub.tenant.TenantContext;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.InvalidDataAccessResourceUsageException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Set;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The backstop that turns "usually applied within milliseconds" into "applied eventually".
 *
 * <p>The drain normally runs straight after a commit, on the instance that served the request.
 * Everything that can interrupt that — the instance dying between commit and drain, Neo4j being
 * down for a while, a row waiting out its retry backoff — leaves committed rows sitting in the
 * table with nobody coming back for them. This sweep is who comes back.
 *
 * <p>It runs on every api instance rather than a designated one: the per-tenant advisory lock
 * already decides who actually drains, so a second sweeper costs one indexed query and nothing
 * else, and no instance being special means recovery does not wait on a particular pod.
 */
@Component
@Slf4j
public class ResourceOutboxSweep {

    private final ResourceOutboxRepository repository;
    private final ResourceOutboxDrainService drainService;
    private final TenantConfigService tenantConfigService;
    private final Duration retention;
    private final int purgeEvery;
    private final AtomicLong passes = new AtomicLong();
    private final AtomicBoolean sweeping = new AtomicBoolean();

    /** Tenants whose table is missing, so the "not provisioned yet" note is logged once, not per tick. */
    private final Set<String> unprovisioned = ConcurrentHashMap.newKeySet();

    /**
     * The sweep runs on its own thread, not the scheduler's. Spring's default scheduler is a single
     * thread shared with everything else annotated {@code @Scheduled} — including the live ingest
     * counter's two-second flush — so a sweep that queried every tenant inline would stall them.
     */
    private final ExecutorService executor = Executors.newSingleThreadExecutor(
            Thread.ofPlatform().name("outbox-sweep").daemon(true).factory());

    public ResourceOutboxSweep(ResourceOutboxRepository repository,
                               ResourceOutboxDrainService drainService,
                               TenantConfigService tenantConfigService,
                               @Value("${datahub.outbox.retention:P7D}") Duration retention,
                               @Value("${datahub.outbox.purge-every:240}") int purgeEvery) {
        this.repository = repository;
        this.drainService = drainService;
        this.tenantConfigService = tenantConfigService;
        this.retention = retention;
        this.purgeEvery = purgeEvery;
    }

    @Scheduled(fixedDelayString = "${datahub.outbox.sweep-ms:15000}")
    public void scheduleSweep() {
        // fixedDelay would normally prevent overlap, but it measures this method, which only hands
        // the work off. Without the guard, a pass slowed by an unreachable tenant database (a
        // connect timeout per tenant) would have another queued behind it every interval, and the
        // executor's queue would grow for as long as the outage lasts.
        if (!sweeping.compareAndSet(false, true)) {
            log.debug("Graph outbox sweep still running; skipping this tick");
            return;
        }
        executor.submit(() -> {
            try {
                sweepAllTenants();
            } finally {
                sweeping.set(false);
            }
        });
    }

    void sweepAllTenants() {
        boolean purge = passes.incrementAndGet() % purgeEvery == 0;
        for (String tenantId : tenantConfigService.cachedTenants.keySet()) {
            try {
                TenantContext.runWith(tenantId, () -> sweepTenant(tenantId, purge));
                if (unprovisioned.remove(tenantId)) {
                    log.info("Graph outbox sweep resumed for tenant {}", tenantId);
                }
            } catch (InvalidDataAccessResourceUsageException e) {
                // The tenant's migrations have not run, so the table is not there yet. That is
                // already reported loudly and repeatedly by TenantFlywayMigrator; repeating it here
                // with a stack trace every tick would bury the report that matters. Say it once.
                if (unprovisioned.add(tenantId)) {
                    log.warn("Graph outbox sweep skipping tenant {} until its schema is provisioned", tenantId);
                }
            } catch (Exception e) {
                // One tenant's database being unreachable must not stop the others being swept.
                log.error("Graph outbox sweep failed for tenant {}: {}", tenantId, e.getMessage(), e);
            }
        }
    }

    private void sweepTenant(String tenantId, boolean purge) {
        if (repository.existsByAppliedAtIsNull()) {
            drainService.requestDrain(tenantId);
        }
        if (purge) {
            purgeApplied();
        }
    }

    /**
     * Applied rows are kept for a while — they are the record of what the mirror did, and the
     * first thing to look at when the graph disagrees with Postgres — then dropped so the table
     * stays the size of its backlog rather than its history.
     */
    void purgeApplied() {
        int removed = repository.deleteAppliedBefore(Instant.now().minus(retention));
        if (removed > 0) {
            log.debug("Purged {} applied graph outbox row(s)", removed);
        }
    }

    @PreDestroy
    void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
    }
}
