// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.models;
import tools.jackson.databind.annotation.JsonSerialize;
import ai.intellistream.datahub.json.ToStringSerializer;

import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class UpdateRelForm {

    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;
    RelFields update;

    public UpdateRelForm(long id){
        this.id = id;
        this.update = new RelFields();
    }

    public UpdateRelForm setId(Long id) {
        this.id = id;
        return this;
    }

    public UpdateRelForm setUpdate(RelFields update) {
        this.update = update;
        return this;
    }
}
