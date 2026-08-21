// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.helpers.updates;

public interface IUpdateField<T> {

    T getSet();

    IUpdateField<T> set(T set);

    Boolean getNull();

    UpdateField<T> setNull(Boolean setNull);

}
