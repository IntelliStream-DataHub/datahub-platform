// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.resource;
import tools.jackson.databind.annotation.JsonSerialize;
import ai.intellistream.datahub.json.ToStringSerializer;

import ai.intellistream.datahub.helpers.text.TextValidator;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class RelTypeForm {

    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    @NotBlank
    @Size(max = 128)
    private String name;
    private String i18nCode;
    private String description;

    public void setName(String name){
        // Keep blank/null input as-is so @NotBlank can reject it. Normalising first would turn
        // whitespace ("   ") into "___" and an empty string stays "", hiding the blank from
        // validation — only normalise once we know there is a real name to normalise.
        this.name = (name == null || name.isBlank()) ? name : TextValidator.toSnakeUpperCased(name);
    }
}
