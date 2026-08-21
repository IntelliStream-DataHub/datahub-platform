// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.helpers.updates;

import ai.intellistream.datahub.models.IdCollection;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;

import java.util.Collection;

@Getter
@Schema(name="UpdateIdCollectionListField", description="What entries you want in the list. Each entry may carry id, externalId, or both.")
public class UpdateIdCollectionListField {

    @Schema(description = "What entries you want in the list. This will remove all existing entries.", example = "[{\"externalId\": \"work_order_sap_1234\"}]")
    private Collection<IdCollection> set;

    @Schema(description = "What entries you want to add to the list. This will keep all existing entries.", example = "[{\"id\": 22}]")
    private Collection<IdCollection> add;

    @Schema(description = "What entries you want to remove from the list. An entry matches on either id or externalId.", example = "[{\"id\": 22}]")
    private Collection<IdCollection> remove;

    @Schema(hidden = true)
    public UpdateIdCollectionListField set(Collection<IdCollection> set) {
        this.set = set;
        return this;
    }

    @Schema(hidden = true)
    public UpdateIdCollectionListField add(Collection<IdCollection> add) {
        this.add = add;
        return this;
    }

    @Schema(hidden = true)
    public UpdateIdCollectionListField remove(Collection<IdCollection> remove) {
        this.remove = remove;
        return this;
    }
}
