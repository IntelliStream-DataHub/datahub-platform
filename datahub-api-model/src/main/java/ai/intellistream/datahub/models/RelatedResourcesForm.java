// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.models;
import tools.jackson.databind.annotation.JsonSerialize;
import ai.intellistream.datahub.json.ToStringSerializer;

import ai.intellistream.datahub.validation.resources.OneIdNotNull;
import jakarta.validation.constraints.Null;
import lombok.Data;

import java.util.List;

@Data
@OneIdNotNull
public class RelatedResourcesForm {

    @Null
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    @Null
    private String externalId;

    /**
     * Maximum number of hops to traverse. {@code -1} (the default) loads the entire connected
     * component reachable from the starting resource; a positive value caps how far out the
     * traversal reaches.
     */
    private Integer depth = -1;

    /**
     * Relationship types the traversal is allowed to follow. {@code null} or empty means
     * "follow every relationship type, in both directions" (the default). When provided, the
     * traversal is restricted to the listed types.
     */
    private List<String> relationshipTypes;

    /**
     * Safety cap on the number of nodes loaded. {@code -1} means unbounded. When the component is
     * larger than this, the nearest {@code limit} nodes are returned (plus every relationship
     * between them).
     */
    private Integer limit = 5000;

    /**
     * Node labels excluded from the traversal. The traversal will neither pass through nor return
     * nodes carrying any of these labels. Defaults to nothing excluded (the whole component is
     * traversed); pass e.g. {@code ["POLICY"]} to skip policy nodes.
     */
    private List<String> excludedLabels = List.of();

}
