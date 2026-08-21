// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.models;
import tools.jackson.databind.annotation.JsonSerialize;
import ai.intellistream.datahub.json.ToStringSerializer;

import ai.intellistream.datahub.helpers.text.TextValidator;
import ai.intellistream.datahub.models.validation.ResourceFields;
import jakarta.validation.constraints.Pattern;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class UpdateResourceForm {

    // The external-id charset floor (see TextValidator.validateExternalIdCharset). Was
    // snake_case-only; relaxed so industrial tags (COM-99-PT-1034, =K1-M3+B02) survive
    // verbatim. A stricter house convention is now a configurable naming policy, not a
    // hard-coded bean constraint.
    @Pattern(regexp = "[A-Za-z0-9._:+=-]+")
    private String externalId;
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;
    ResourceFields update;

    public UpdateResourceForm(Long id){
        this.id = id;
        this.update = new ResourceFields();
    }

    public UpdateResourceForm setExternalId(String externalId) {
        this.externalId = externalId;
        return this;
    }

    public UpdateResourceForm setId(Long id) {
        this.id = id;
        return this;
    }

    public UpdateResourceForm setUpdate(ResourceFields update) {
        this.update = update;
        return this;
    }
}
