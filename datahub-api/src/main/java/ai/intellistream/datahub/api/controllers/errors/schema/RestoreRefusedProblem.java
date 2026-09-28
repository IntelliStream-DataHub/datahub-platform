// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.controllers.errors.schema;

import io.swagger.v3.oas.annotations.media.Schema;

/** A 409 for a file that cannot go back where it came from. Documentation only. */
@Schema(name = "RestoreRefusedProblem",
        description = """
                Nothing was restored. `reason` says what stands in the way.""")
public class RestoreRefusedProblem extends ApiProblem {

    @Schema(description = """
            `path-taken` (a file exists at its original path now), `not-a-file` (only files can be \
            restored), `external-id-taken` (another file uses its externalId now), \
            `trash-entry-missing` (the file has no entry in the trash) or \
            `folder-missing` (its original folder is gone).""",
            allowableValues = {"path-taken", "not-a-file", "external-id-taken", "trash-entry-missing",
                    "folder-missing"},
            example = "folder-missing")
    private String reason;

    public String getReason() { return reason; }
}
