// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.clickhouse.filter;

/**
 * What an operand evaluates to, which is how the renderer decides whether a comparison is
 * meaningful and which ClickHouse parameter type a literal beside it should bind as.
 *
 * <p>Coarser than ClickHouse's own type system on purpose: it exists to catch "you are comparing
 * a string to a datetime" before ClickHouse does, and to pick a parameter type, not to model the
 * database.
 */
public enum ValueType {
    STRING,
    NUMBER,
    DATETIME,
    BOOLEAN,
    /** A UUID column. Compared against a string literal bound as {@code UUID}. */
    UUID
}
