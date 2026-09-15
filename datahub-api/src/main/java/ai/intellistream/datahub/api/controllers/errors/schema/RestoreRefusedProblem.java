// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.controllers.errors.schema;

import io.swagger.v3.oas.annotations.media.Schema;

/** A 409 for a file that cannot go back where it came from. Documentation only. */
@Schema(name = "RestoreRefusedProblem",
        description = """
                Nothing was restored. `reason` says what stands in the way; without one, a file \
                already exists at the original path.""")
public class RestoreRefusedProblem extends ApiProblem {

    @Schema(description = """
            `not-a-file` (only files can be restored), `external-id-taken` (another file uses its \
            externalId now), `external-id-unrecoverable` (its original externalId could not be \
            recovered) or `folder-missing` (its original folder is gone).""",
            allowableValues = {"not-a-file", "external-id-taken", "external-id-unrecoverable", "folder-missing"},
            example = "folder-missing")
    private String reason;

    public String getReason() { return reason; }
}
