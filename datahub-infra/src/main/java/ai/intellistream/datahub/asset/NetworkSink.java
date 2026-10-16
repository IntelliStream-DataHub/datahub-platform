// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.asset;

import ai.intellistream.datahub.models.EdgeProxy;
import ai.intellistream.datahub.models.NodeModel;

/**
 * Receives a network one piece at a time, as it is read from Neo4j, instead of all at once: for
 * example the JSON writer that sends each piece straight to the browser. All edges come first, then
 * all resources, because a resource's list of neighbours ({@code relatedResources}) can only be filled
 * in once every edge is known.
 */
public interface NetworkSink {
    void edge(EdgeProxy edge);

    void node(NodeModel node);
}
