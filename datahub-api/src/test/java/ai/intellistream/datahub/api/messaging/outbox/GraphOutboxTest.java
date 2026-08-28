// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.messaging.outbox;

import ai.intellistream.datahub.jpa.domains.AssetEntity;
import ai.intellistream.datahub.jpa.domains.EdgeEntity;
import ai.intellistream.datahub.jpa.domains.ResourceOutboxEntity;
import ai.intellistream.datahub.repositories.outbox.ResourceOutboxRepository;
import ai.intellistream.datahub.services.graph.GraphSyncCommand;
import ai.intellistream.datahub.services.graph.GraphSyncCommandCodec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * What ends up in the queue for a given change. This is the one place that decides which ids a
 * change touches and whether they are being written or removed, so it is worth testing on its own
 * rather than only through the services.
 */
class GraphOutboxTest {

    private final ResourceOutboxRepository repository = mock(ResourceOutboxRepository.class);
    private final ResourceOutboxDrainService drainService = mock(ResourceOutboxDrainService.class);
    private final GraphOutbox outbox = new GraphOutbox(repository, drainService);

    @BeforeEach
    void enterTransaction() {
        // Stands in for the caller's @Transactional; the synchronization the outbox registers
        // needs an active one to attach to.
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
    }

    @AfterEach
    void leaveTransaction() {
        // Spring unbinds the drain marker in afterCompletion, which only runs under a real
        // transaction manager. Without doing it here the marker leaks to the next test on this
        // thread, and that test then sees a drain as already scheduled.
        List.copyOf(TransactionSynchronizationManager.getResourceMap().keySet())
                .forEach(TransactionSynchronizationManager::unbindResourceIfPossible);
        TransactionSynchronizationManager.setActualTransactionActive(false);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void anUpsertQueuesTheIdsOfTheEntitiesItWasGiven() {
        outbox.queueUpsert(List.of(asset(1L), asset(2L)), List.of(edge(10L)));

        GraphSyncCommand queued = captureQueued();
        assertThat(queued.upsertNodeIds()).containsExactly(1L, 2L);
        assertThat(queued.upsertEdgeIds()).containsExactly(10L);
        assertThat(queued.deleteNodes()).isEmpty();
        assertThat(queued.deleteEdgeIds()).isEmpty();
    }

    @Test
    void aDeleteQueuesIdentifiersInlineBecauseTheRowsWillBeGone() {
        outbox.queueDelete(List.of(new GraphSyncCommand.NodeRef(1L, "asset-1")), List.of(10L));

        GraphSyncCommand queued = captureQueued();
        assertThat(queued.deleteNodes()).containsExactly(new GraphSyncCommand.NodeRef(1L, "asset-1"));
        assertThat(queued.deleteEdgeIds()).containsExactly(10L);
        assertThat(queued.upsertNodeIds()).isEmpty();
    }

    @Test
    void anEntityMentionedTwiceIsQueuedOnce() {
        outbox.queueUpsert(List.of(asset(3L), asset(3L)), List.of());

        assertThat(captureQueued().upsertNodeIds()).containsExactly(3L);
    }

    @Test
    void aChangeThatTouchesNothingQueuesNothing() {
        outbox.queueUpsert(List.of(), List.of());

        verify(repository, never()).save(any());
    }

    @Test
    void anUnflushedEntityIsRefusedRatherThanSilentlyDropped() {
        // Queuing an entity with no id would mean a change the graph never hears about, which is
        // exactly the silent divergence the outbox exists to prevent.
        assertThatThrownBy(() -> outbox.queueUpsert(List.of(asset(null)), List.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("before it has an id");
    }

    @Test
    void idsGivenDirectlyMayBeAbsentBecauseUpdateFormsCanNameARowByExternalId() {
        outbox.queueUpsertIds(java.util.Arrays.asList(5L, null), List.of());

        assertThat(captureQueued().upsertNodeIds()).containsExactly(5L);
    }

    @Test
    void queuingOutsideATransactionIsRefused() {
        // save() is transactional in its own right, so without this check the row would commit on
        // its own — describing a change that may still roll back.
        TransactionSynchronizationManager.setActualTransactionActive(false);

        assertThatThrownBy(() -> outbox.queueUpsert(List.of(asset(1L)), List.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("@Transactional");
        verify(repository, never()).save(any());
    }

    @Test
    void anEmptyChangeStillChecksForATransactionSoTheMistakeSurfacesOnTheFirstCall() {
        TransactionSynchronizationManager.setActualTransactionActive(false);

        assertThatThrownBy(() -> outbox.queueUpsert(List.of(), List.of()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void severalQueuesInOneTransactionScheduleOneDrain() {
        outbox.queueUpsert(List.of(asset(1L)), List.of());
        outbox.queueUpsert(List.of(asset(2L)), List.of());

        assertThat(TransactionSynchronizationManager.getSynchronizations()).hasSize(1);
    }

    private GraphSyncCommand captureQueued() {
        ArgumentCaptor<ResourceOutboxEntity> row = ArgumentCaptor.forClass(ResourceOutboxEntity.class);
        verify(repository).save(row.capture());
        return GraphSyncCommandCodec.fromJson(row.getValue().getPayload());
    }

    private static AssetEntity asset(Long id) {
        AssetEntity asset = new AssetEntity();
        asset.setId(id);
        return asset;
    }

    private static EdgeEntity edge(Long id) {
        EdgeEntity edge = new EdgeEntity();
        edge.setId(id);
        return edge;
    }
}
