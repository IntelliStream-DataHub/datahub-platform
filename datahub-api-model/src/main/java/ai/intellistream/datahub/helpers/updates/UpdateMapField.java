// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.helpers.updates;

import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.Getter;
import lombok.Setter;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;


@Schema(name="UpdateMapField", description="What value you want the map field set to.")
@Data
public class UpdateMapField {

    @Schema(description = "What values you want the map field to. This will remove all existing entries.", example = "{\"topic\": \"message\"}")
    @JsonSetter(nulls = Nulls.SET)
    private Map<String, String> set;

    @Schema(description = "What values you want to add to the map. This will keep all existing entries.", example = "{\"topic\": \"message\"}")
    @JsonSetter(nulls = Nulls.SET)
    private Map<String, String> add;

    @Schema(description = "What values you want to remove from the map.", example = "{\"topic\": \"message\"}")
    @JsonSetter(nulls = Nulls.SET)
    private Collection<String> remove;



    @Schema(hidden = true)
    public UpdateMapField add(Map<String, String> add) {
        this.add = add;
        return this;
    }

    @Schema(hidden = true)
    public UpdateMapField remove(Collection<String> remove) {
        this.remove = remove;
        return this;
    }
}
