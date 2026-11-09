// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.flow.flows;

import ai.intellistream.datahub.flow.flows.FlowRepository.StoredFlow;
import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.List;

/**
 * A flow as the REST API shows it.
 *
 * @param kind            {@code flow} or {@code agent}
 * @param version         the current (latest saved) version
 * @param deployedVersion the version that is deployed, or null
 * @param definition      the current version's definition; left out of lists
 * @param disabledReason  why the service switched the flow off, such as {@code DATASET_DELETED}
 *                        when its dataset is gone; absent otherwise
 * @param createdSeries   the series this save created for sinks with {@code create}; only on a save
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record FlowView(String externalId, String name, String dataSet, String dataSetId, String kind,
                       int version, Integer deployedVersion, boolean enabled, String disabledReason,
                       String createdBy, Instant createdAt, String updatedBy, Instant updatedAt,
                       JsonNode definition, List<String> createdSeries) {

    static FlowView of(StoredFlow flow, JsonNode definition, List<String> createdSeries) {
        return new FlowView(flow.externalId(), flow.name(), flow.dataSetExternalId(),
                // Ids go out as strings, like every node id on the wire: they are beyond 2^53.
                String.valueOf(flow.dataSetId()), flow.kind(), flow.currentVersion(), flow.deployedVersion(),
                flow.enabled(), flow.disabledReason(), flow.createdBy(), flow.createdAt(),
                flow.updatedBy(), flow.updatedAt(), definition, createdSeries);
    }

    /** A saved version. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Version(int version, String createdBy, Instant createdAt, JsonNode definition) {
    }

    /**
     * What {@code POST /flows/validate} answers.
     *
     * @param series the series saving it would create, where they do not exist yet
     */
    public record Validation(boolean valid, List<Problem> problems, List<String> series) {
    }

    /** One thing wrong, at a JSON Pointer into the definition. */
    public record Problem(String field, String message) {
    }
}
