// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.models;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Direction of a relationship as seen from the node it is attached to. A node-centric relation list mixes
 * both directions of the underlying (directed) edges, so each entry records whether the edge points away
 * from this node ({@link #OUTBOUND}, this node is the edge {@code start}) or towards it ({@link #INBOUND},
 * this node is the edge {@code end}).
 */
@Schema(name = "RelationDirection", description = "Direction of a relation relative to the node it is attached to.")
public enum RelationDirection {
    OUTBOUND,
    INBOUND
}
