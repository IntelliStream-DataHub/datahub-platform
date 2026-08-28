// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.messaging.outbox;

import ai.intellistream.datahub.jpa.domains.ResourceOutboxEntity;
import ai.intellistream.datahub.repositories.outbox.ResourceOutboxRepository;
import ai.intellistream.datahub.services.graph.GraphSyncCommand;
import ai.intellistream.datahub.services.graph.GraphSyncCommandCodec;
import ai.intellistream.datahub.services.graph.Neo4jSchemaInitializer;
import ai.intellistream.datahub.services.graph.ResourceGraphApplier;
import ai.intellistream.datahub.tenant.TenantContext;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Applies queued graph-sync commands to Neo4j, one tenant at a time.
 *
 * <h2>What serialises the writers</h2>
 * A batch runs inside a Postgres transaction that first takes the tenant's advisory lock. The
 * lock lives in the tenant's own database, so it arbitrates between every api instance, not just
 * the threads of one JVM: exactly one drainer per tenant is applying at any moment, which is the
 * ordering guarantee the single-active Pulsar consumer used to provide. An instance that cannot
 * take the lock simply returns — someone else is already doing the work.
 *
 * <h2>Why the transaction stays open across the graph writes</h2>
 * The batch reads its rows, applies them, and stamps the outcome in one transaction. Holding it
 * open across Neo4j calls costs a pooled connection, which is why the batch is bounded; in return,
 * a crash needs no recovery logic at all. The lock and the unwritten stamps disappear together,
 * the rows stay pending, and the next drain re-applies them — harmlessly, because the applier
 * writes current Postgres state rather than replaying a diff.
 */
@Service
@Slf4j
public class ResourceOutboxDrainService {

    /**
     * Rows per transaction. Bounds how long one pooled Postgres connection is held across graph
     * I/O; a tenant with more work simply loops.
     */
    private static final int BATCH_SIZE = 100;

    /** Errors are stored to be read by a human, not to reproduce a stack trace. */
    private static final int MAX_ERROR_LENGTH = 2000;

    private final ResourceOutboxRepository repository;
    private final ResourceGraphApplier applier;
    private final Neo4jSchemaInitializer schemaInitializer;
    private final TransactionTemplate transactionTemplate;
    private final Duration baseBackoff;
    private final Duration maxBackoff;

    /** One flag per tenant: whether a drain is running, and whether one more pass is owed. */
    private final Map<String, AtomicBoolean> running = new ConcurrentHashMap<>();
    private final Map<String, AtomicBoolean> rerun = new ConcurrentHashMap<>();

    private final ExecutorService executor;

    public ResourceOutboxDrainService(ResourceOutboxRepository repository,
                                      ResourceGraphApplier applier,
                                      Neo4jSchemaInitializer schemaInitializer,
                                      PlatformTransactionManager transactionManager,
                                      @Value("${datahub.outbox.drain-threads:3}") int drainThreads,
                                      @Value("${datahub.outbox.base-backoff:PT5S}") Duration baseBackoff,
                                      @Value("${datahub.outbox.max-backoff:PT10M}") Duration maxBackoff) {
        this.repository = repository;
        this.applier = applier;
        this.schemaInitializer = schemaInitializer;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        // The drain must never join a caller's transaction: it commits its own progress, and the
        // after-commit trigger has none to join anyway.
        this.transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.baseBackoff = baseBackoff;
        this.maxBackoff = maxBackoff;
        this.executor = Executors.newFixedThreadPool(drainThreads, Thread.ofPlatform()
                .name("outbox-drain-", 0)
                .daemon(true)
                .factory());
    }

    /**
     * Asks for the tenant's queue to be drained, without waiting for it. Repeated calls while a
     * drain is in flight collapse into one extra pass, so a burst of writes does not queue a
     * drain per write.
     */
    public void requestDrain(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            return;
        }
        AtomicBoolean active = running.computeIfAbsent(tenantId, key -> new AtomicBoolean());
        if (!active.compareAndSet(false, true)) {
            rerun.computeIfAbsent(tenantId, key -> new AtomicBoolean()).set(true);
            return;
        }
        try {
            executor.submit(() -> drainLoop(tenantId));
        } catch (RuntimeException e) {
            active.set(false);
            throw e;
        }
    }

    private void drainLoop(String tenantId) {
        AtomicBoolean active = running.get(tenantId);
        try {
            boolean more = true;
            while (more) {
                more = TenantContext.callWith(tenantId, () -> drainOnce(tenantId));
                if (!more && rerun.getOrDefault(tenantId, new AtomicBoolean()).getAndSet(false)) {
                    more = true;
                }
            }
        } catch (Exception e) {
            // The rows are still pending and the sweep will come back for them; nothing here is lost.
            log.error("Graph outbox drain failed for tenant {}: {}", tenantId, e.getMessage(), e);
        } finally {
            active.set(false);
        }
    }

    /**
     * One batch. Returns true when there may be more work to do immediately.
     *
     * <p>Failures stop the batch where they are: the queue is ordered, and skipping past a row to
     * apply a later one would let the graph converge to a state Postgres never passed through.
     */
    public boolean drainOnce(String tenantId) {
        return Boolean.TRUE.equals(transactionTemplate.execute(status -> {
            if (!repository.tryDrainLock(ResourceOutboxRepository.DRAIN_LOCK_KEY)) {
                return false;
            }
            List<ResourceOutboxEntity> pending =
                    repository.findByAppliedAtIsNullOrderByIdAsc(Limit.of(BATCH_SIZE));
            if (pending.isEmpty()) {
                return false;
            }
            Instant now = Instant.now();
            // The head gates the queue: a row waiting out its backoff holds everything behind it,
            // which is what keeps a tenant's changes in order rather than merely eventually applied.
            if (pending.get(0).getNextAttemptAt() != null && pending.get(0).getNextAttemptAt().isAfter(now)) {
                return false;
            }
            schemaInitializer.ensureConstraints(tenantId);

            List<Long> applied = new ArrayList<>(pending.size());
            ResourceOutboxEntity failed = null;
            Exception failure = null;
            for (ResourceOutboxEntity row : pending) {
                try {
                    GraphSyncCommand command = GraphSyncCommandCodec.fromJson(row.getPayload());
                    applier.apply(command, tenantId);
                    applied.add(row.getId());
                } catch (Exception e) {
                    failed = row;
                    failure = e;
                    break;
                }
            }
            if (!applied.isEmpty()) {
                repository.markApplied(applied, Instant.now());
            }
            if (failed != null) {
                recordFailure(tenantId, failed, failure);
                return false;
            }
            return pending.size() == BATCH_SIZE;
        }));
    }

    private void recordFailure(String tenantId, ResourceOutboxEntity row, Exception failure) {
        int attempts = row.getAttempts() + 1;
        Duration backoff = backoffFor(attempts);
        String error = failure.toString();
        if (error.length() > MAX_ERROR_LENGTH) {
            error = error.substring(0, MAX_ERROR_LENGTH);
        }
        repository.recordFailure(row.getId(), attempts, Instant.now().plus(backoff), error);
        // No attempt ceiling on purpose. The consumer this replaces dropped a message after ten
        // tries, which left the mirror wrong with nothing but a log line to say so. A row that
        // cannot be applied now blocks its tenant's queue visibly instead: attempts and last_error
        // say what is stuck and why, and an operator can skip it deliberately by stamping applied_at.
        log.error("Graph outbox row {} failed for tenant {} (attempt {}, retrying in {}): {}",
                row.getId(), tenantId, attempts, backoff, failure.getMessage(), failure);
    }

    /** Exponential backoff, doubling from the base and saturating at the cap. */
    Duration backoffFor(int attempts) {
        Duration backoff = baseBackoff;
        for (int i = 1; i < attempts; i++) {
            if (backoff.compareTo(maxBackoff) >= 0) {
                return maxBackoff;
            }
            backoff = backoff.multipliedBy(2);
        }
        return backoff.compareTo(maxBackoff) > 0 ? maxBackoff : backoff;
    }

    @PreDestroy
    void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
    }
}
