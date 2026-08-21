// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.models.forms;
import tools.jackson.databind.annotation.JsonSerialize;
import tools.jackson.databind.annotation.JsonDeserialize;
import ai.intellistream.datahub.json.ToStringSerializer;
import ai.intellistream.datahub.json.TimestampDeserializer;

import ai.intellistream.datahub.models.validation.AllowedAggregates;
import ai.intellistream.datahub.validation.resources.AtLeastOneNotNull;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collection;

/**
 * Datapoint retrieval criteria. Not a {@code NodeFilter}: it selects points within one
 * timeseries by time window, not nodes by their columns. It used to extend the old
 * {@code DataFilter} purely to inherit four min/max timestamp fields that nothing read, and it
 * has always carried its own {@link #start}/{@link #end} instead.
 */
@Data
@AtLeastOneNotNull(fieldNames = {"start", "end"})
@NoArgsConstructor
public class RetrieveFilter {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonDeserialize(using = TimestampDeserializer.class)
    private ZonedDateTime start;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonDeserialize(using = TimestampDeserializer.class)
    private ZonedDateTime end;

    @Min(0)
    @Max(100000)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Integer limit = 0;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @AllowedAggregates
    private Collection<String> aggregates = new ArrayList<>();

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String granularity;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Boolean includeOutsidePoints = false;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String cursor;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String externalId;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Boolean mergeDuplicates = false;

    public Integer getLimit(){
        if(limit == null){
            limit = 100;
        }
        return limit;
    }

    public Collection<String> getAggregates(){
        if(aggregates == null){
            aggregates = new ArrayList<>();
        }
        return aggregates;
    }

}
