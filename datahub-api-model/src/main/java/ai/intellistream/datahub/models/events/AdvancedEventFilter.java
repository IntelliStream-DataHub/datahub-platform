// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.models.events;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(name="AdvancedFilter", description = "The advanced filter feature allows you to build more advanced filtering expressions by combining operations like equals and exists using the Boolean operators AND, OR, and NOT.")
public class AdvancedEventFilter extends AdvancedFilter {

    @JsonCreator
    public AdvancedEventFilter(
            @JsonProperty("not") AdvancedNotFilter not,
            @JsonProperty("and") List<AdvancedFilter> and,
            @JsonProperty("or") List<AdvancedFilter> or,
            @JsonProperty("operator") @Schema(hidden = true) AdvancedFilterOperator operator,
            @JsonProperty("property") List<String> property,
            @JsonProperty("value") String value,
            @JsonProperty("values") List<String> values
    ) {
        super();
        setNot(not);
        setAnd(and);
        setOr(or);
        setFilterOperator(operator);
        setProperty(property);
        setValue(value);
        setValues(values);
    }

    public boolean validate(){
        var invalidPropertyNames = findInvalidPropertyNames(this);
        if(!invalidPropertyNames.isEmpty()){
            createAndThrowError(invalidPropertyNames);
        }
        return true;
    }

    @Override
    public boolean isValidProperty(String property) {
        if (property == null || property.isBlank()) return false;
        return switch (property) {
            case "id", "externalId", "type", "subType", "source", "dataSetId", "metadata" -> true;
            default -> false;
        };
    }
}
