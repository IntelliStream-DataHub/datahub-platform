// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.helpers.updates;

import lombok.Getter;

@Getter
public class UpdateField<T> implements IUpdateField<T>{

    private T set;
    private Boolean setNull;

    @Override
    public UpdateField<T> set(T set) {
        this.set = set;
        return this;
    }

    @Override
    public Boolean getNull() {
        return setNull;
    }

    @Override
    public UpdateField<T> setNull(Boolean setNull) {
        this.setNull = setNull;
        return this;
    }
}
