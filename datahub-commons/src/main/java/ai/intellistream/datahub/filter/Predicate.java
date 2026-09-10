// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.filter;

import java.util.List;

/**
 * A boolean node of a parsed events filter expression.
 *
 * <p><b>There is deliberately no node carrying SQL text.</b> A tree of these is only ever produced
 * by {@link EventFilterParser} from a caller's expression, and the renderer builds every character
 * of the emitted SQL from fixed keywords, an allow-list of column names and generated parameter
 * placeholders. A "raw SQL" node would be the one hole in that, so the type system does not offer
 * one — the safe path is the only path rather than the remembered one.
 *
 * <p>Negation has exactly one representation: {@link Not}. {@code NOT LIKE}, {@code NOT IN},
 * {@code NOT BETWEEN} and {@code IS NOT NULL} are all parsed into a {@code Not} wrapping the
 * positive form, so the renderer has one case to get right instead of five.
 */
public sealed interface Predicate
        permits Predicate.And, Predicate.Or, Predicate.Not, Predicate.Comparison,
                Predicate.Like, Predicate.In, Predicate.Between, Predicate.IsNull,
                Predicate.BooleanValue {

    /** All of these must hold. Never empty; a one-element list is collapsed by the parser. */
    record And(List<Predicate> nodes) implements Predicate {
        public And {
            nodes = List.copyOf(nodes);
        }
    }

    /** Any of these may hold. Never empty; a one-element list is collapsed by the parser. */
    record Or(List<Predicate> nodes) implements Predicate {
        public Or {
            nodes = List.copyOf(nodes);
        }
    }

    record Not(Predicate node) implements Predicate {
    }

    record Comparison(Expr left, CompareOp op, Expr right) implements Predicate {
    }

    /** {@code LIKE} and its case-insensitive twin. Postgres spells the latter {@code ILIKE}. */
    record Like(Expr left, Expr pattern, boolean caseInsensitive) implements Predicate {
    }

    record In(Expr left, List<Expr> values) implements Predicate {
        public In {
            values = List.copyOf(values);
        }
    }

    record Between(Expr left, Expr low, Expr high) implements Predicate {
    }

    record IsNull(Expr operand) implements Predicate {
    }

    /**
     * A boolean-valued operand used directly as a condition, e.g. {@code has_key('site')}.
     * The renderer rejects one whose type is not boolean, so {@code type} alone is not a filter.
     */
    record BooleanValue(Expr operand) implements Predicate {
    }
}
