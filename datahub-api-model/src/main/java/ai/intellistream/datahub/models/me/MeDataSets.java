// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.models.me;

import ai.intellistream.datahub.json.ToStringSerializer;
import io.swagger.v3.oas.annotations.media.Schema;
import tools.jackson.databind.annotation.JsonSerialize;

import java.util.List;

/**
 * What {@code GET /me/datasets} answers: the datasets the caller may read, or write.
 *
 * <p>{@code all} means every dataset in the tenant — the wildcard grant, or {@code DATAHUB_ADMIN} —
 * and then {@code ids} is empty rather than a list of everything. Otherwise {@code ids} lists the
 * datasets granted, with a grant on a parent already expanded to every dataset beneath it. A
 * client must check {@code all} first: an empty {@code ids} on its own means no access.
 *
 * <p>Ids go out as strings, like every node id on the wire: they are xxHash values beyond 2^53.
 */
public record MeDataSets(
        @Schema(description = "Whether the caller has this access to every dataset in the tenant.")
        boolean all,
        @JsonSerialize(contentUsing = ToStringSerializer.class)
        @Schema(description = "The dataset ids, after expanding grants down the dataset hierarchy."
                + " Empty when all is true.", example = "[\"2323\"]")
        List<Long> ids) {
}
