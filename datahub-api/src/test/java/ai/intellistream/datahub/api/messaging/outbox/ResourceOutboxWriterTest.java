// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.messaging.outbox;

import ai.intellistream.datahub.models.EdgeProxy;
import ai.intellistream.datahub.models.Resource;
import ai.intellistream.datahub.models.UpdateResourceForm;
import ai.intellistream.datahub.pulsar.EventAction;
import ai.intellistream.datahub.pulsar.EventObject;
import ai.intellistream.datahub.pulsar.ResourceCudMessage;
import ai.intellistream.datahub.services.graph.GraphSyncCommand;
import ai.intellistream.datahub.services.graph.GraphSyncCommandCodec;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The mapping from "what a service changed" to "what the graph must re-read", and the round trip
 * through the outbox column. Both are pure data, so neither needs a database.
 */
class ResourceOutboxWriterTest {

    @Test
    void createCollectsNodeAndEdgeIdsToUpsert() {
        ResourceCudMessage message =
                new ResourceCudMessage(EventAction.CREATE, EventObject.RESOURCE_AND_RELATION, "tenant");
        message.setResources(List.of(resource(1L, "asset-1"), resource(2L, "asset-2")));
        message.setEdges(List.of(edge(10L)));

        GraphSyncCommand command = ResourceOutboxWriter.toCommand(message);

        assertThat(command.upsertNodeIds()).containsExactly(1L, 2L);
        assertThat(command.upsertEdgeIds()).containsExactly(10L);
        assertThat(command.deleteNodes()).isEmpty();
        assertThat(command.deleteEdgeIds()).isEmpty();
    }

    @Test
    void deleteCollectsIdentifiersInline() {
        // A delete cannot be resolved from Postgres later — the row is gone — so both identifiers
        // travel with the command.
        ResourceCudMessage message =
                new ResourceCudMessage(EventAction.DELETE, EventObject.RESOURCE_AND_RELATION, "tenant");
        message.setResources(List.of(resource(1L, "asset-1")));
        message.setEdges(List.of(edge(10L)));

        GraphSyncCommand command = ResourceOutboxWriter.toCommand(message);

        assertThat(command.deleteNodes())
                .containsExactly(new GraphSyncCommand.NodeRef(1L, "asset-1"));
        assertThat(command.deleteEdgeIds()).containsExactly(10L);
        assertThat(command.upsertNodeIds()).isEmpty();
    }

    @Test
    void updateFormsAreUpsertsToo() {
        ResourceCudMessage message =
                new ResourceCudMessage(EventAction.UPDATE, EventObject.RESOURCE_AND_RELATION, "tenant");
        UpdateResourceForm form = new UpdateResourceForm();
        form.setId(7L);
        message.setUpdateResourceForms(List.of(form));

        assertThat(ResourceOutboxWriter.toCommand(message).upsertNodeIds()).containsExactly(7L);
    }

    @Test
    void idsMentionedTwiceAreQueuedOnce() {
        // ResourceService names a node both as a resource and in an update form; re-reading it
        // twice in one command would be wasted work, not a correctness problem.
        ResourceCudMessage message =
                new ResourceCudMessage(EventAction.UPDATE, EventObject.RESOURCE_AND_RELATION, "tenant");
        message.setResources(List.of(resource(3L, "asset-3")));
        UpdateResourceForm form = new UpdateResourceForm();
        form.setId(3L);
        message.setUpdateResourceForms(List.of(form));

        assertThat(ResourceOutboxWriter.toCommand(message).upsertNodeIds()).containsExactly(3L);
    }

    @Test
    void aMessageThatTouchesNothingProducesNoRow() {
        ResourceCudMessage message =
                new ResourceCudMessage(EventAction.CREATE, EventObject.RESOURCE_AND_RELATION, "tenant");

        assertThat(ResourceOutboxWriter.toCommand(message).isEmpty()).isTrue();
    }

    @Test
    void commandSurvivesTheOutboxColumn() {
        GraphSyncCommand command = new GraphSyncCommand(
                List.of(1L, 2L), List.of(10L),
                List.of(new GraphSyncCommand.NodeRef(3L, "asset-3"),
                        new GraphSyncCommand.NodeRef(null, "asset-4")),
                List.of(11L));

        GraphSyncCommand restored = GraphSyncCommandCodec.fromJson(GraphSyncCommandCodec.toJson(command));

        assertThat(restored).isEqualTo(command);
    }

    @Test
    void anOlderPayloadStillParsesAfterTheCommandGrows() {
        // A row queued by the previous deploy must still drain after this one starts: unknown keys
        // are ignored and absent lists default to empty rather than null.
        GraphSyncCommand restored = GraphSyncCommandCodec.fromJson(
                "{\"upsertNodeIds\":[5],\"somethingAddedLater\":\"x\"}");

        assertThat(restored.upsertNodeIds()).containsExactly(5L);
        assertThat(restored.upsertEdgeIds()).isEmpty();
        assertThat(restored.deleteNodes()).isEmpty();
    }

    private static Resource resource(Long id, String externalId) {
        Resource resource = new Resource();
        resource.setId(id);
        resource.setExternalId(externalId);
        return resource;
    }

    private static EdgeProxy edge(Long id) {
        EdgeProxy edge = new EdgeProxy();
        edge.setId(id);
        return edge;
    }
}
