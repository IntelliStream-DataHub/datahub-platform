// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.controllers.errors.schema;

import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.Map;

/**
 * A 404 for a write that mostly succeeded. Documentation only.
 *
 * <p>Unusual, and deliberate: data-point insert does not fail a whole batch because one target is
 * unknown. The rest were written; {@code missing} names what was not, so the caller can create
 * those series and retry just them.
 */
@Schema(name = "PartialWriteProblem",
        description = """
                Some targets of the write did not exist. Their part of the batch was skipped and \
                the rest was written — this is a partial success, not a failed request.""")
public class PartialWriteProblem extends ApiProblem {

    @ArraySchema(
            arraySchema = @Schema(description = "The targets that were skipped."),
            schema = @Schema(example =
                    "{\"externalId\": \"does_not_exist\", \"id\": \"null\"}"))
    private List<Map<String, String>> missing;

    public List<Map<String, String>> getMissing() { return missing; }
}
