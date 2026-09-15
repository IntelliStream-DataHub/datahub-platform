// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.controllers.errors.schema;

import io.swagger.v3.oas.annotations.media.Schema;

/** One rejected input, as published. Documentation only. */
@Schema(name = "FieldProblem", description = "A single input the API refused, and why.")
public class FieldProblem {

    @Schema(description = "The property path, e.g. `externalId` or `items[0].name`.",
            example = "externalId")
    private String field;

    @Schema(description = "The resolved, human-readable reason.", example = "must not be blank")
    private String message;

    @Schema(description = """
            An i18n key for the rule that rejected it, so the message can be phrased in the \
            caller's own words instead of parsed out of prose. Absent where the throw site had \
            no key.""",
            example = "resource.source.max.length.error")
    private String code;

    @Schema(description = """
            The bound a length or count broke, or for an `externalId` outside the allowed \
            characters, the externalId itself. Any JSON type. Absent for every other rule, so a \
            refused value is never echoed back.""",
            example = "129")
    private Object rejected;

    public String getField() { return field; }
    public String getMessage() { return message; }
    public String getCode() { return code; }
    public Object getRejected() { return rejected; }
}
