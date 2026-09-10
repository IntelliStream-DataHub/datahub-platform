// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.filter;

/**
 * The comparison operators the filter language offers.
 *
 * <p>{@code !=} and {@code <>} both parse to {@link #NEQ}: Postgres accepts either, and a caller
 * should not have to know which one this dialect picked.
 */
public enum CompareOp {
    EQ("="),
    NEQ("!="),
    LT("<"),
    LTE("<="),
    GT(">"),
    GTE(">=");

    private final String sql;

    CompareOp(String sql) {
        this.sql = sql;
    }

    /** The operator as SQL. A fixed string from this enum, never anything the caller typed. */
    public String sql() {
        return sql;
    }
}
