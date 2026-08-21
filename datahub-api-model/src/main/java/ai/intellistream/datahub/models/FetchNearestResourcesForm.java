// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.models;

import ai.intellistream.datahub.json.ToStringSerializer;
import ai.intellistream.datahub.validation.resources.OneIdNotNull;
import lombok.Data;
import tools.jackson.databind.annotation.JsonSerialize;

import java.util.List;

/**
 * Request for the nearest-N graph traversal ({@code POST /resources/fetch-nearest}): breadth-first
 * from a starting resource, returning the closest {@code limit} nodes carrying one of
 * {@code endLabels} plus the sub-graph that connects them. Unlike {@link RelatedResourcesForm}
 * (capped by hop {@code depth} / total node count), this caps by the number of matching END-nodes —
 * so "the 10 nearest TIMESERIES" is exact regardless of how many intermediate nodes lie between them.
 */
@Data
@OneIdNotNull
public class FetchNearestResourcesForm {

    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    private String externalId;

    /** Node labels that qualify as an end-node (e.g. {@code ["TIMESERIES"]}); traversal continues past them. */
    private List<String> endLabels;

    /** Nearest-N cap: the closest this many matching end-nodes are returned (plus the connecting sub-graph). */
    private Integer limit = 10;

    /** Relationship types the traversal may follow. {@code null}/empty = all types. */
    private List<String> relationshipTypes;

    /** Node labels the traversal neither passes through nor returns (e.g. {@code ["POLICY"]}). */
    private List<String> excludedLabels = List.of();
}
