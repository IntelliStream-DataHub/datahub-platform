// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.helpers.updates;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;

import java.util.Collection;

@Getter
@Schema(name="UpdateNumberListField", description="What numeric values you want in the list.")
public class UpdateNumberListField {

    @Schema(description = "What numeric values you want in the list. This will remove all existing entries.", example = "[122, 1235]")
    private Collection<Long> set;

    @Schema(description = "What numeric values you want in the list. This will keep all existing entries.", example = "[122, 1235]")
    private Collection<Long> add;

    @Schema(description = "What numeric values you want to remove from the list.", example = "[122, 1235]")
    private Collection<Long> remove;

    @Schema(hidden = true)
    public UpdateNumberListField set(Collection<Long> set) {
        this.set = set;
        return this;
    }

    @Schema(hidden = true)
    public UpdateNumberListField add(Collection<Long> add) {
        this.add = add;
        return this;
    }

    @Schema(hidden = true)
    public UpdateNumberListField remove(Collection<Long> remove) {
        this.remove = remove;
        return this;
    }
}
