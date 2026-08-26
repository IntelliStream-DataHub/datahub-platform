// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.models;

import com.fasterxml.jackson.annotation.JsonInclude;
import ai.intellistream.datahub.helpers.text.TextValidator;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import ai.intellistream.datahub.json.ToStringSerializer;
import io.swagger.v3.oas.annotations.media.Schema;
import tools.jackson.databind.annotation.JsonSerialize;
import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

@Getter @Setter
@Schema(name = "Data Set", description = "Data Set object")
@JsonPropertyOrder({"id", "externalId", "name", "*"})
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DataSetModel extends NodeModel {

    public DataSetModel() {
        // Seeded through the shared setter; NodeModel applies the type-label (see typeLabel()), so
        // the DTO self-types even before the server stamps the full label set.
        setLabels(new ArrayList<>());
    }

    @Override
    protected String typeLabel() {
        return "DATASET";
    }

    @Schema(description = "Add policies to the data set, the policy is the external id of the resource.", example = "policy_is_write_protected")
    private List<String> policies = new ArrayList<>();

    /**
     * Serialised as strings, like every other id on the wire. These are xxHash node ids, so a
     * JavaScript client round-tripping them through a double silently corrupts anything past
     * 2^53 — this was the last id-shaped list still emitted as raw numbers.
     */
    @JsonSerialize(contentUsing = ToStringSerializer.class)
    @Schema(description = "The ids of the data sets this data set is part of.", example = "[\"2323\"]")
    private List<Long> connectedDataSets = new ArrayList<>();

    public void setPolicies(List<String> policies) {
        if (policies != null) {
            this.policies = policies.stream().map(TextValidator::toSnakeLowerCasedAllowStartWithDigits).toList();
        }
    }

}
