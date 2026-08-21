// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.models.events;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class AdvancedFilter implements IAdvancedFilter {

    private AdvancedNotFilter not;
    private List<AdvancedFilter> and;
    private List<AdvancedFilter> or;

    @JsonIgnore
    @Schema(hidden = true)
    private AdvancedFilterOperator filterOperator;

    @JsonIgnore
    @Schema(hidden = true)
    private Operator operator;

    private List<String> property;
    private String value;
    private List<String> values;

    public AdvancedFilter() {}

    protected void createAndThrowError(List<String> invalidPropertyNames) {
        throw new AdvancedFilterException(invalidPropertyNames);
    }

    public void setNot(AdvancedNotFilter not) {
        this.not = not;
    }

    protected List<String> findInvalidPropertyNames(AdvancedFilter filter){

        List<String> invalidPropertyNames = new ArrayList<>();

        if(filter.getOr() != null){
            for(AdvancedFilter f : filter.getOr()){
                invalidPropertyNames.addAll( findInvalidPropertyNames(f) );
            }
        } else if (filter.getAnd() != null) {
            for(AdvancedFilter f : filter.getAnd()){
                invalidPropertyNames.addAll( findInvalidPropertyNames(f) );
            }
        } else if (filter.getNot() != null) {
            invalidPropertyNames.addAll( findInvalidPropertyNames(filter.getNot()) );
        } else if (filter.getProperty() != null && !filter.getProperty().isEmpty()) {
            String propertyName = filter.getProperty().getFirst();
            if(!isValidProperty(propertyName)){
                invalidPropertyNames.add(propertyName);
            }
        }

        return invalidPropertyNames;
    }

    protected boolean isValidProperty(String property) {
        if (property == null || property.isBlank()) return false;
        return switch (property) {
            case "id", "externalId", "metadata" -> true;
            default -> false;
        };
    }

    @JsonProperty("equals")
    public void setEquals(AdvancedFilterOperator advancedFilterOperator) {
        advancedFilterOperator.setOperator( Operator.equals );
        this.filterOperator = advancedFilterOperator;
    }

    @JsonProperty("prefix")
    public void setPrefix(AdvancedFilterOperator advancedFilterOperator) {
        advancedFilterOperator.setOperator( Operator.prefix );
        this.filterOperator = advancedFilterOperator;
    }

    @JsonProperty("in")
    public void setIn(AdvancedFilterOperator advancedFilterOperator) {
        advancedFilterOperator.setOperator( Operator.in );
        this.filterOperator = advancedFilterOperator;
    }

    @Override
    public boolean validate() {
        return false;
    }
}
