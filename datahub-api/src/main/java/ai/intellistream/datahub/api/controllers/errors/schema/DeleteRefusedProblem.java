// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.controllers.errors.schema;

import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.Map;

/**
 * A 409 for a delete something still depends on. Documentation only.
 *
 * <p>{@code blockedBy} is the actionable half: without it the caller knows the delete failed but
 * not which subscription to remove or which resources to include.
 */
@Schema(name = "DeleteRefusedProblem",
        description = """
                The delete conflicts with the current state; nothing was removed, and the same \
                request succeeds once the conflict is resolved. Branch on `type`: \
                `.../errors/referenced` (a subscription still reads the timeseries — remove it \
                first), `.../errors/would-strand` (the delete would cut surviving nodes off from \
                the graph root — include them, or keep a connecting path), or \
                `.../errors/optimistic-lock` (re-read and retry, no `blockedBy`).""")
public class DeleteRefusedProblem extends ApiProblem {

    @ArraySchema(
            arraySchema = @Schema(description =
                    "What stands in the way, one entry per blocker. The keys depend on `type`: "
                            + "`subscriptionId`/`subscriptionExternalId`/`timeseriesId` for "
                            + "`referenced`, `externalId` for `would-strand`."),
            schema = @Schema(example =
                    "{\"subscriptionId\": \"91\", \"subscriptionExternalId\": \"fleet_dashboard\","
                            + " \"timeseriesId\": \"5677892\"}"))
    private List<Map<String, String>> blockedBy;

    public List<Map<String, String>> getBlockedBy() { return blockedBy; }
}
