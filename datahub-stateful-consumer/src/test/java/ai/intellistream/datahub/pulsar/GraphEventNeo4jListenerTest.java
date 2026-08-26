// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.pulsar;

import static org.junit.jupiter.api.Assertions.assertTrue;
import java.util.Map;
import ai.intellistream.datahub.pulsar.EventObject;
import ai.intellistream.datahub.pulsar.EventAction;
import ai.intellistream.datahub.models.Resource;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GraphEventNeo4jListenerTest {

    @Test
    void keepsCurrentLabelsWhenNothingRequested() {
        assertEquals(List.of("ASSET", "Pump"),
                GraphEventNeo4jListener.computeNewLabels(List.of("ASSET", "Pump"), null, null, null));
    }

    @Test
    void addAppendsToCurrentLabels() {
        // The bug: "add" used to be ignored (or invert to a remove). It must actually add.
        assertEquals(List.of("ASSET", "Pump", "Critical"),
                GraphEventNeo4jListener.computeNewLabels(List.of("ASSET", "Pump"), null, List.of("Critical"), null));
    }

    @Test
    void addIsIdempotentAgainstExistingLabels() {
        assertEquals(List.of("ASSET", "Pump"),
                GraphEventNeo4jListener.computeNewLabels(List.of("ASSET", "Pump"), null, List.of("Pump"), null));
    }

    @Test
    void removeDropsLabels() {
        assertEquals(List.of("ASSET"),
                GraphEventNeo4jListener.computeNewLabels(List.of("ASSET", "Pump"), null, null, List.of("Pump")));
    }

    @Test
    void setReplacesTheWholeLabelSet() {
        assertEquals(List.of("ASSET", "Valve"),
                GraphEventNeo4jListener.computeNewLabels(List.of("ASSET", "Pump"), List.of("ASSET", "Valve"), null, null));
    }

    @Test
    void emptySetIsIgnoredLikeTheApiSide() {
        // ResourceService skips an empty set (Postgres labels untouched), so the graph must too —
        // otherwise {"labels":{"set":[]}} strips every Neo4j label while Postgres keeps them.
        assertEquals(List.of("ASSET", "Pump"),
                GraphEventNeo4jListener.computeNewLabels(List.of("ASSET", "Pump"), List.of(), null, null));
    }

    @Test
    void setThenAddThenRemoveComposeInOrder() {
        // (set) ASSET,Valve + add Critical - remove Valve  ->  ASSET, Critical
        assertEquals(List.of("ASSET", "Critical"),
                GraphEventNeo4jListener.computeNewLabels(
                        List.of("ASSET", "Pump"),
                        List.of("ASSET", "Valve"),
                        List.of("Critical"),
                        List.of("Valve")));
    }
    /**
     * The point of Phase 6: the api now sends the labels Postgres actually resolved, and the
     * consumer applies those instead of re-deriving them. Re-deriving was a fourth implementation
     * of the label rules and the only one that did not enforce the type-label, so a `set` that
     * omitted it stripped it from the graph while Postgres kept it — the two stores then disagreed
     * about what kind of node it was.
     */
    @Test
    void resolvedLabelsAreTakenFromTheMessageWhenPresent() {
        var message = new ResourceCudMessage(
                EventAction.UPDATE, EventObject.RESOURCE_AND_RELATION, "tenant-1");
        Resource resolved = new Resource();
        resolved.setId(5L);
        resolved.setLabels(List.of("ASSET", "PUMP"));
        message.setResources(List.of(resolved));

        Map<Long, List<String>> byId = GraphEventNeo4jListener.resolvedLabelsFrom(message);

        assertEquals(List.of("ASSET", "PUMP"), byId.get(5L));
    }

    /** No resources on the message: an api from before Phase 6, so the walk falls back. */
    @Test
    void aMessageWithoutResourcesResolvesToNothing() {
        var message = new ResourceCudMessage(
                EventAction.UPDATE, EventObject.RESOURCE_AND_RELATION, "tenant-1");

        assertTrue(GraphEventNeo4jListener.resolvedLabelsFrom(message).isEmpty());
    }

}
