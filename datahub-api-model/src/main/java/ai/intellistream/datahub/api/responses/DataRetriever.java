// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.api.responses;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;

import java.util.ArrayList;
import java.util.Collection;

@Data
public class DataRetriever<T> {

    @Valid
    private Collection<T> items = new ArrayList<>();

    private String start;
    private String end;

    @Min(0)
    @Max(100000)
    private Integer limit = 0;

    private Collection<String> aggregates = new ArrayList<>();

    private String granularity;

    private Boolean includeOutsidePoints = false;

    private Boolean ignoreUnknownIds = false;

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public Collection<T> getItems() {
        return items;
    }

    public DataRetriever<T> setItems(Collection<T> items) {
        this.items = items;
        return this;
    }

}
