// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.messaging.outbox;

import ai.intellistream.datahub.api.messaging.events.ResourceCudPublishEvent;
import ai.intellistream.datahub.jpa.domains.ResourceOutboxEntity;
import ai.intellistream.datahub.models.EdgeProxy;
import ai.intellistream.datahub.models.Resource;
import ai.intellistream.datahub.models.UpdateRelForm;
import ai.intellistream.datahub.models.UpdateResourceForm;
import ai.intellistream.datahub.pulsar.EventAction;
import ai.intellistream.datahub.pulsar.ResourceCudMessage;
import ai.intellistream.datahub.repositories.outbox.ResourceOutboxRepository;
import ai.intellistream.datahub.services.graph.GraphSyncCommand;
import ai.intellistream.datahub.services.graph.GraphSyncCommandCodec;
import ai.intellistream.datahub.timeseries.UpdateTimeseries;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.core.JacksonException;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns a resource CUD event into a queued graph-sync command, in the same transaction as the
 * change itself.
 *
 * <p>This is the half of the outbox that provides the guarantee. The row and the node it
 * describes commit together or roll back together, which closes the window the previous
 * after-commit Pulsar publish left open: a crash between the two lost the graph update outright,
 * and the code said so.
 *
 * <p>Draining is deliberately a separate, after-commit concern — a graph write must never be able
 * to fail, slow down, or roll back a caller's transaction.
 */
@Component
@Slf4j
public class ResourceOutboxWriter {

    private final ResourceOutboxRepository repository;
    private final ResourceOutboxDrainService drainService;

    public ResourceOutboxWriter(ResourceOutboxRepository repository, ResourceOutboxDrainService drainService) {
        this.repository = repository;
        this.drainService = drainService;
    }

    /**
     * Writes the outbox row inside the publishing transaction.
     *
     * <p>Registered with {@code fallbackExecution} so it also runs when there is no transaction —
     * only to fail loudly. Without it, publishing from a method that someone forgot to make
     * {@code @Transactional} would skip this listener silently and drop the graph update, which is
     * the precise failure this table exists to eliminate.
     */
    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT, fallbackExecution = true)
    public void writeOutboxRow(ResourceCudPublishEvent event) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(
                    "ResourceCudPublishEvent was published outside a transaction. The graph outbox row "
                            + "must commit with the change it describes — make the publishing method @Transactional.");
        }
        GraphSyncCommand command = toCommand(event.message());
        if (command.isEmpty()) {
            return;
        }
        try {
            repository.save(new ResourceOutboxEntity(GraphSyncCommandCodec.toJson(command)));
        } catch (JacksonException e) {
            // Fail the caller's transaction: a change we cannot queue is a change the graph would
            // never learn about, and silently diverging is what this design set out to stop.
            throw new IllegalStateException("Could not serialize graph sync command", e);
        }
    }

    /** Kicks the tenant's queue once the data is durable. The sweep is the backstop if this misses. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void triggerDrain(ResourceCudPublishEvent event) {
        drainService.requestDrain(event.message().getTenantId());
    }

    /**
     * Reduces the message to the ids it touched. Values are deliberately dropped — the applier
     * reads current state from Postgres — so this only has to answer "what changed", not "to what".
     */
    static GraphSyncCommand toCommand(ResourceCudMessage message) {
        List<Long> upsertNodes = new ArrayList<>();
        List<Long> upsertEdges = new ArrayList<>();
        List<GraphSyncCommand.NodeRef> deleteNodes = new ArrayList<>();
        List<Long> deleteEdges = new ArrayList<>();

        boolean delete = message.getEventAction() == EventAction.DELETE;
        for (Resource resource : message.getResources()) {
            if (delete) {
                deleteNodes.add(new GraphSyncCommand.NodeRef(resource.getId(), resource.getExternalId()));
            } else if (resource.getId() != null) {
                upsertNodes.add(resource.getId());
            }
        }
        for (EdgeProxy edge : message.getEdges()) {
            if (edge.getId() == null) {
                continue;
            }
            if (delete) {
                deleteEdges.add(edge.getId());
            } else {
                upsertEdges.add(edge.getId());
            }
        }
        // Update forms name the same rows a second way; an update is an upsert either way.
        for (UpdateResourceForm form : message.getUpdateResourceForms()) {
            if (form.getId() != null) {
                upsertNodes.add(form.getId());
            }
        }
        for (UpdateTimeseries form : message.getUpdateTimeseries()) {
            if (form.getId() != null) {
                upsertNodes.add(form.getId());
            }
        }
        for (UpdateRelForm form : message.getUpdateEdges()) {
            if (form.getId() != null) {
                upsertEdges.add(form.getId());
            }
        }
        return new GraphSyncCommand(upsertNodes.stream().distinct().toList(),
                upsertEdges.stream().distinct().toList(),
                deleteNodes,
                deleteEdges.stream().distinct().toList());
    }
}
