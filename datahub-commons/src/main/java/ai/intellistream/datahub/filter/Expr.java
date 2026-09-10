// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.filter;

import java.math.BigDecimal;
import java.util.List;

/**
 * A value node: the two sides of a comparison, and the arguments of a function call.
 *
 * <p>Names here are the ones the caller wrote, unresolved on purpose. {@link ColumnRef} holds
 * {@code "subType"}, not {@code sub_type}, and {@link FunctionCall} holds {@code "to_timestamp"},
 * not {@code parseDateTimeBestEffortOrNull}. Resolving either is the renderer's job, because both
 * mappings are facts about ClickHouse and this module deliberately knows nothing about it — which
 * is also what lets the parser be tested without a database.
 */
public sealed interface Expr
        permits Expr.ColumnRef, Expr.MetadataRef, Expr.FunctionCall,
                Expr.StringLiteral, Expr.NumberLiteral, Expr.BooleanLiteral, Expr.Cast {

    /** A bare identifier, as written. Case is preserved; resolution is case-insensitive. */
    record ColumnRef(String name) implements Expr {
    }

    /** {@code metadata['key']}. The key is always a literal, so it can always be bound. */
    record MetadataRef(String key) implements Expr {
    }

    record FunctionCall(String name, List<Expr> args) implements Expr {
        public FunctionCall {
            args = List.copyOf(args);
        }
    }

    record StringLiteral(String value) implements Expr {
    }

    record NumberLiteral(BigDecimal value) implements Expr {
    }

    /** {@code true} / {@code false}, as Postgres spells them. */
    record BooleanLiteral(boolean value) implements Expr {
    }

    /**
     * Postgres {@code ::} cast sugar. It resolves to the same converter the {@code to_*()}
     * functions do, so {@code metadata['n']::int} and {@code to_int(metadata['n'])} render
     * identically and there is one set of rules rather than two.
     */
    record Cast(Expr operand, String typeName) implements Expr {
    }
}
