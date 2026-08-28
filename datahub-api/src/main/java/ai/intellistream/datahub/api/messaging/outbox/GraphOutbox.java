// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.messaging.outbox;

import ai.intellistream.datahub.jpa.domains.EdgeEntity;
import ai.intellistream.datahub.jpa.domains.NodeEntity;
import ai.intellistream.datahub.jpa.domains.ResourceOutboxEntity;
import ai.intellistream.datahub.repositories.outbox.ResourceOutboxRepository;
import ai.intellistream.datahub.services.graph.GraphSyncCommand;
import ai.intellistream.datahub.services.graph.GraphSyncCommandCodec;
import ai.intellistream.datahub.tenant.TenantContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * Queues a change for the Neo4j graph mirror, in the transaction that makes the change.
 *
 * <p>This is the half of the outbox that provides the guarantee: the row and the rows it describes
 * commit together or roll back together. The window the old after-commit Pulsar publish left open —
 * a crash between commit and send, losing the graph update outright — cannot exist here.
 *
 * <p>Only <em>which</em> rows changed is recorded, never their values. {@code ResourceGraphApplier}
 * re-reads them from Postgres when it applies, so a command queued now and applied after two more
 * edits writes the state Postgres holds then, not the state it held here.
 *
 * <p>Draining is deliberately a separate, after-commit concern: mirroring must never be able to
 * fail, slow down, or roll back a caller's transaction.
 */
@Component
@Slf4j
public class GraphOutbox {

    /** Marks a transaction as already having a drain scheduled, so N queues fire one drain. */
    private static final Object DRAIN_SCHEDULED = new Object();

    private final ResourceOutboxRepository repository;
    private final ResourceOutboxDrainService drainService;

    public GraphOutbox(ResourceOutboxRepository repository, ResourceOutboxDrainService drainService) {
        this.repository = repository;
        this.drainService = drainService;
    }

    /**
     * Mirror these nodes and edges as they stand at drain time. Ids are read from the entities, so
     * they must already be assigned — call after the flush that assigns them.
     */
    public void queueUpsert(Collection<? extends NodeEntity> nodes, Collection<EdgeEntity> edges) {
        requireTransaction();
        queue(new GraphSyncCommand(idsOf(nodes, NodeEntity::getId), idsOf(edges, EdgeEntity::getId),
                List.of(), List.of()));
    }

    /**
     * Mirror these nodes and edges, for callers that hold ids rather than entities. Null ids are
     * skipped rather than rejected: these come from update forms, which may identify a row by
     * external id instead. (Such a row is not mirrored — the same gap the previous writer had.)
     */
    public void queueUpsertIds(Collection<Long> nodeIds, Collection<Long> edgeIds) {
        requireTransaction();
        queue(new GraphSyncCommand(distinct(nodeIds), distinct(edgeIds), List.of(), List.of()));
    }

    /**
     * Remove these nodes and edges from the graph. Deletes carry their identifiers inline because
     * the rows are gone by the time this is applied, leaving nothing to read them from.
     */
    public void queueDelete(Collection<GraphSyncCommand.NodeRef> nodes, Collection<Long> edgeIds) {
        requireTransaction();
        queue(new GraphSyncCommand(List.of(), List.of(),
                nodes.stream().filter(Objects::nonNull).distinct().toList(), distinct(edgeIds)));
    }

    private void queue(GraphSyncCommand command) {
        requireTransaction();
        if (command.isEmpty()) {
            return;
        }
        repository.save(new ResourceOutboxEntity(GraphSyncCommandCodec.toJson(command)));
        scheduleDrainOnce();
    }

    /**
     * The first thing every entry point does, before it so much as reads the arguments. Spring
     * Data's {@code save()} is transactional in its own right, so without this it would open a
     * transaction of its own and commit the row independently — queuing a change for data that may
     * still roll back, which is the dual write this table exists to remove. Checked ahead of any
     * complaint about the arguments so a caller that forgot {@code @Transactional} is told about
     * that, which is the fault that matters.
     */
    private static void requireTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(
                    "A graph outbox row must be written inside the transaction that makes the change it "
                            + "describes. Make the calling method @Transactional.");
        }
    }

    /**
     * Drains the tenant's queue once this transaction commits. The tenant is captured here, on the
     * request thread, because the callback runs after {@code TenantContext} may have been cleared.
     */
    private void scheduleDrainOnce() {
        if (TransactionSynchronizationManager.hasResource(DRAIN_SCHEDULED)) {
            return;
        }
        String tenantId = TenantContext.getTenantId();
        TransactionSynchronizationManager.bindResource(DRAIN_SCHEDULED, Boolean.TRUE);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                drainService.requestDrain(tenantId);
            }

            @Override
            public void afterCompletion(int status) {
                TransactionSynchronizationManager.unbindResourceIfPossible(DRAIN_SCHEDULED);
            }
        });
    }

    /**
     * Ids of persisted entities. A null id means the caller has not flushed yet, and silently
     * dropping it would leave the graph missing a row nobody would ever notice — so it throws.
     */
    private static <T> List<Long> idsOf(Collection<? extends T> items, Function<T, Long> id) {
        if (items == null) {
            return List.of();
        }
        return items.stream().filter(Objects::nonNull).map(item -> {
            Long value = id.apply(item);
            if (value == null) {
                throw new IllegalStateException(
                        "Cannot queue " + item.getClass().getSimpleName() + " for the graph before it has an id. "
                                + "Flush the entity first.");
            }
            return value;
        }).distinct().toList();
    }

    private static List<Long> distinct(Collection<Long> ids) {
        return ids == null ? List.of() : ids.stream().filter(Objects::nonNull).distinct().toList();
    }
}
