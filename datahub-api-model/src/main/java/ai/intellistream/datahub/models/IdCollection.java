// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.models;
import ai.intellistream.datahub.helpers.text.ExternalIds;
import tools.jackson.databind.annotation.JsonSerialize;
import ai.intellistream.datahub.json.ToStringSerializer;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(name = "IdCollection", description = "IdCollection object that contains id or external id.")
public class IdCollection {

    @Schema(description = "The id of the object,", example = "5677892", required = false)
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    @Schema(description = "The external id of the object,", example = "kl_33PP3_sensor_alarm_temperature", required = false)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String externalId;

    public static IdCollection createFromId(long id){
        var instance = new IdCollection();
        instance.id = id;
        return instance;
    }

    public static IdCollection createFromExternalId(String externalId){
        var instance = new IdCollection();
        instance.externalId = externalId;
        return instance;
    }

    @JsonIgnore
    public Long getExternalIdHash() {
        if (externalId == null) {return null;}
        else {return ExternalIds.hash(externalId);}
    }
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public String getExternalId(){
        return this.externalId;
    }

}
