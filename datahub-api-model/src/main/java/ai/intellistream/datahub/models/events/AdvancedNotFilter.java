// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.models.events;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;

@EqualsAndHashCode(callSuper = true)
@Data
@Schema(name="AdvancedNotFilter", description = "The advanced not filter feature allows you to combine NOT operators.")
public class AdvancedNotFilter extends AdvancedFilter {

    @JsonCreator
    public AdvancedNotFilter(
            @JsonProperty("not") @Schema(hidden = true) AdvancedNotFilter not,
            @JsonProperty("and") List<AdvancedFilter> and,
            @JsonProperty("or") List<AdvancedFilter> or,
            @JsonProperty("operator") @Schema(hidden = true) AdvancedFilterOperator operator,
            @JsonProperty("property") List<String> property,
            @JsonProperty("value") String value,
            @JsonProperty("values") List<String> values
    ) {
        super();
        setNot(null);
        setAnd(and);
        setOr(or);
        setFilterOperator(operator);
        setProperty(property);
        setValue(value);
        setValues(values);
    }

    @Override
    @Schema(hidden = true)
    public void setNot(AdvancedNotFilter not){

    }

}
