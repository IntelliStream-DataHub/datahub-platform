// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.function;

import ai.intellistream.datahub.json.ToStringSerializer;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Pattern;
import lombok.Getter;
import tools.jackson.databind.annotation.JsonSerialize;

/**
 * One entry of {@code POST /functions/update}: which function, by {@code id} or
 * {@code externalId}, and the {@link FunctionFields} to change on it.
 */
@Getter
@Schema(name = "UpdateFunctionForm", description = "A function to update and the fields to change on it")
public class UpdateFunctionForm {

    @Schema(description = "The id of the function. Takes precedence over externalId.", example = "123466453")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    @Schema(description = "The external id of the function.", example = "fn_rolling_average")
    // The external-id charset floor, as on UpdateResourceForm.
    @Pattern(regexp = "[A-Za-z0-9._:+=-]+")
    private String externalId;

    private FunctionFields update = new FunctionFields();

    public UpdateFunctionForm setId(Long id) {
        this.id = id;
        return this;
    }

    public UpdateFunctionForm setExternalId(String externalId) {
        this.externalId = externalId;
        return this;
    }

    public UpdateFunctionForm setUpdate(FunctionFields update) {
        this.update = update;
        return this;
    }
}
