// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.controllers.errors.schema;

import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.Map;

/**
 * A 409 for an identifier the tenant already uses. Documentation only.
 *
 * <p>Also the shape of the optimistic-lock conflict, which carries no extension — the two are told
 * apart by {@code type}, not by the presence of {@code duplicated}.
 */
@Schema(name = "DuplicateProblem",
        description = """
                A conflict with what is already stored. `type` distinguishes \
                `.../errors/duplicate` (that identifier is taken — pick another, or use the \
                matching `/update`) from `.../errors/optimistic-lock` (another request changed \
                the row between your read and your write — re-read and retry).""")
public class DuplicateProblem extends ApiProblem {

    @ArraySchema(
            arraySchema = @Schema(description =
                    "The identifiers that collided, as field-to-value pairs. Absent for a "
                            + "conflict that could not be attributed to a named identifier."),
            schema = @Schema(example = "{\"externalId\": \"sensor_temp_room_a\"}"))
    private List<Map<String, String>> duplicated;

    @ArraySchema(
            arraySchema = @Schema(description =
                    "The fields that collided, where the database named the field but not the value "
                            + "and so `duplicated` is absent."),
            schema = @Schema(implementation = FieldProblem.class))
    private List<FieldProblem> fields;

    public List<Map<String, String>> getDuplicated() { return duplicated; }
    public List<FieldProblem> getFields() { return fields; }
}
