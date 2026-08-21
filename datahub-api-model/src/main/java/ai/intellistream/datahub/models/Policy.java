// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.models;
import tools.jackson.databind.annotation.JsonSerialize;
import ai.intellistream.datahub.json.ToStringSerializer;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
@Schema(description = "Add policies to the data set.")
@JsonPropertyOrder({"id", "externalId", "name", "*"})
public class Policy extends NodeModel {

    public Policy() {
        // Seeded through the shared setter; NodeModel applies the type-label (see typeLabel()).
        setLabels(new ArrayList<>());
    }

    @Override
    protected String typeLabel() {
        return "POLICY";
    }

    @NotNull
    @Schema(description = "If you want to write data to a write-protected data set, you need to be a member of a group that has the \"datasets:owner\" policy for the data set. Read more: [Owner policy docs](https://intellistream.ai/documentation/datasets#owner)\"\n ", example = "IS_WRITE_PROTECTED or IS_READ_PROTECTED or REQUIREMENT")
    private PolicyType type;

    @Schema(description = "Policy value, can be boolean, text or number", example = "TRUE, FALSE, 1001, 'FOOBAR'")
    private Object value;

    // No @NotNull: the field is a primitive, so the constraint could never fail. Absence of the
    // property means "not deactivated", which is what the default already expresses.
    @Schema(description = "If the policy is deactivated, it will not be enforced.", example = "false")
    private boolean isDeactivated = false;

    @Schema(description = "Node type (always POLICY when node)")
    private String nodeType;

    @Schema(description = "Template ID applied to this policy node", example = "3")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long templateId;

}
