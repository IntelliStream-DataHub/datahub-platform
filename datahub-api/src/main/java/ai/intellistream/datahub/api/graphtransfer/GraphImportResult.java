package ai.intellistream.datahub.api.graphtransfer;

import ai.intellistream.datahub.models.policy.PolicyWarning;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.Collection;
import java.util.List;

/**
 * Outcome of a graph import: what was created, and what the import deliberately left alone.
 * Skipping is not an error — re-importing a file into the tenant it came from should be a no-op,
 * so nodes that already exist (matched by externalId) are skipped rather than rejected.
 */
@Schema(name = "GraphImportResult", description = "Summary of an imported graph file.")
public record GraphImportResult(
        @Schema(description = "Resources created.", example = "42")
        int nodesCreated,
        @Schema(description = "Relationships created.", example = "41")
        int relationsCreated,
        @Schema(description = "Resources skipped because a node with the same externalId already exists.", example = "3")
        int nodesSkippedExisting,
        @Schema(description = "Timeseries in the file that do not exist here and cannot be created "
                + "through the resource api — recreate them through the timeseries api first.")
        List<String> nodesSkippedTimeseries,
        @Schema(description = "Relationships skipped: already present, or an endpoint is unavailable.", example = "1")
        int relationsSkipped,
        @Schema(description = "Nodes whose dataset reference could not be resolved here and was dropped.", example = "0")
        int dataSetReferencesDropped,
        @Schema(description = "Naming-policy violations that were allowed through and recorded for review.")
        Collection<PolicyWarning> warnings) {
}
