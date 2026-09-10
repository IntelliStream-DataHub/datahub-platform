// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.models.datafilters;

/**
 * One term of a WHERE clause, with the column and value it came from for debugging.
 *
 * <p>{@code sql} is always a self-contained boolean expression: the assembler joins terms with
 * AND and never inspects them. The record used to carry an {@code SQLOperation} as well, so a
 * term could ask to be joined with OR — which is how a caller's boolean structure came to sit at
 * the same precedence as the dataset ACL. Terms no longer choose their own join.
 */
public record SqlField(String column, Object value, String sql) {
}
