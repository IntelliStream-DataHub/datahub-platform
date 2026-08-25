// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.transformers;

import ai.intellistream.datahub.jpa.domains.NodeType;

import java.util.List;
import ai.intellistream.datahub.jpa.domains.PolicyEntity;
import ai.intellistream.datahub.models.Policy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;

/**
 * Guards the one thing about this transformer that keeps breaking: which associations it touches.
 *
 * <p>Policies are rendered outside the service transaction and {@code open-in-view} is off, so any
 * lazy association read here is either an extra SELECT per policy (session still open) or a
 * {@code LazyInitializationException} (entity detached). That has bitten twice — once when
 * NodeEntity's associations were flipped to LAZY, and again on {@code GET /policies} via the
 * metadata map. A plain assertion on the output cannot catch a regression, because reading the
 * association would produce the same value; these tests assert on the *access* instead.
 */
class PolicyTransformerTest {

    /**
     * Stands in for a policy whose lazy {@code nodeType} proxy cannot be resolved — a detached
     * entity, exactly what the transformer receives after the transaction closes. Hibernate would
     * throw LazyInitializationException; any throw proves the association was touched.
     */
    private static class DetachedNodeTypePolicy extends PolicyEntity {
        @Override
        public NodeType getNodeType() {
            throw new IllegalStateException(
                    "nodeType was dereferenced — outside a session this is LazyInitializationException");
        }
    }

    @Test
    @DisplayName("toPolicy never dereferences the lazy nodeType association")
    void doesNotTouchNodeType() {
        PolicyEntity entity = new DetachedNodeTypePolicy();
        entity.setName("IS_WRITE_PROTECTED");

        // Fails with the IllegalStateException above if anyone makes the transformer read
        // item.getNodeType(). The DTO no longer carries a nodeType field at all — the POLICY
        // type-label in labels is the wire discriminator, seeded by the Policy constructor —
        // so the association has nothing left to feed.
        Policy policy = PolicyTransformer.toPolicy(entity);

        assertEquals(List.of("POLICY"), policy.getLabels());
    }

    @Test
    @DisplayName("The type travels as the POLICY label, whatever the association would say")
    void typeLabelIsConstant() {
        PolicyEntity entity = new PolicyEntity();
        entity.setName("NAMING_CONVENTION");
        NodeType wrong = new NodeType();
        wrong.setName("ASSET");
        entity.setNodeType(wrong);

        // Every policy query filters node_type = 6, so the association is redundant by construction
        // and the transformer must not depend on it being populated — or populated correctly.
        assertEquals(List.of("POLICY"), PolicyTransformer.toPolicy(entity).getLabels());
    }

    @Test
    @DisplayName("Metadata is copied, so a lazy PersistentMap never reaches JSON serialization")
    void metadataIsCopiedNotAliased() {
        PolicyEntity entity = new PolicyEntity();
        entity.setName("IS_WRITE_PROTECTED");
        entity.setMetadata(new java.util.HashMap<>(Map.of("kind", "naming")));

        Policy policy = PolicyTransformer.toPolicy(entity);

        // The July fix: handing the entity's own map to the DTO deferred initialization to
        // serialization time, long after the session closed.
        assertNotSame(entity.getMetadata(), policy.getMetadata());
        assertEquals("naming", policy.getMetadata().get("kind"));
    }

    @Test
    @DisplayName("The deactivated column round-trips")
    void carriesDeactivated() {
        PolicyEntity entity = new PolicyEntity();
        entity.setName("IS_WRITE_PROTECTED");
        entity.setDeactivated(true);

        assertEquals(true, PolicyTransformer.toPolicy(entity).isDeactivated());
    }
}
