// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.helpers.updates;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;

import java.util.Collection;

@Getter
@Schema(name="UpdateListField", description="What values you want to set the list to")
public class UpdateListField {

    @Schema(description = "What values you want in the list. This will remove all existing entries.", example = "[\"topic\", \"message\"]")
    private Collection<String> set;

    @Schema(description = "What values you want to add to the list. This will keep all existing entries.", example = "[\"topic\", \"message\"]")
    private Collection<String> add;

    @Schema(description = "What values you want to remove from the list.", example = "[\"topic\", \"message\"]")
    private Collection<String> remove;

    @Schema(hidden = true)
    public UpdateListField set(Collection<String> set) {
        this.set = set;
        return this;
    }

    @Schema(hidden = true)
    public UpdateListField add(Collection<String> add) {
        this.add = add;
        return this;
    }

    @Schema(hidden = true)
    public UpdateListField remove(Collection<String> remove) {
        this.remove = remove;
        return this;
    }
}
