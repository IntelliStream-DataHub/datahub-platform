// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.models.datafilters;

import ai.intellistream.datahub.models.events.SQLOperation;

public record SqlField(String column, Object value, String sql, SQLOperation sqlOperation) {

    public SqlField(String column, Object value, String sql) {
        this(column, value, sql, null);
    }
}
