// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.services.node;

import ai.intellistream.datahub.jpa.domains.NodeEntity;
import ai.intellistream.datahub.models.validation.ResourceFields;

/**
 * The one part of a node update that varies by node type: mapping the fields only <em>this</em>
 * kind of node has. Everything else — resolving the target, the ACL, the naming policy, the
 * type-label guard, label reconciliation, persistence, the CUD event — belongs to
 * {@link NodeUpdateService} and is applied to every type alike, so a new node type gets all of it
 * by declaring one of these rather than by growing another update method that has to remember.
 *
 * <p>Keyed by entity class rather than by a type name: the discriminator is already expressed in
 * the Java type, so a strategy cannot drift from the entity it serves the way a string key could,
 * and a typo is a compile error.
 */
public interface NodeUpdateStrategy {

    /**
     * The entity class this strategy serves. A node is matched to the nearest registered
     * ancestor, so a proxied or otherwise subclassed instance still finds it.
     */
    Class<? extends NodeEntity> handles();

    /**
     * Apply this type's own fields. Called after the shared fields are set and before labels and
     * dataset membership, which the engine owns. The entity is managed, so mutating it is the
     * write; there is nothing to return.
     */
    void apply(NodeEntity node, ResourceFields fields);
}
