// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.controllers.errors.schema;

import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * A 400 naming the inputs that were refused. Documentation only.
 *
 * <p>The same {@code fields} list whether the rule ran at the binding layer, in a bean validator
 * inside a service, or in a hand-written check — a caller correcting their request should not have
 * to care which. Empty or absent when the failure has no per-field breakdown.
 */
@Schema(name = "ValidationProblem",
        description = "A rejected request, with the offending inputs listed in `fields`.")
public class ValidationProblem extends ApiProblem {

    @ArraySchema(
            arraySchema = @Schema(description =
                    "One entry per rejected input. Absent when the failure names no field."),
            schema = @Schema(implementation = FieldProblem.class))
    private List<FieldProblem> fields;

    public List<FieldProblem> getFields() { return fields; }
}
