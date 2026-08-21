// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.pulsar;

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
}
