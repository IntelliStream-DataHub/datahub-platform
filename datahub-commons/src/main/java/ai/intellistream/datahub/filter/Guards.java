// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.filter;

/**
 * Bounds on a parsed expression's shape.
 *
 * <p>A parser is an attack surface for cost as much as for injection: a deeply nested or very wide
 * expression is cheap to send and expensive to plan and execute. These are checked on the built
 * tree rather than in the grammar so the error can name which limit was reached, which a grammar
 * rule cannot do.
 */
final class Guards {

    private Guards() {
    }

    static void check(Predicate root) {
        Counts counts = new Counts();
        walk(root, 1, counts);
    }

    private static final class Counts {
        int nodes;
        int functionCalls;
    }

    private static void walk(Predicate node, int depth, Counts counts) {
        if (depth > EventFilterParser.MAX_DEPTH) {
            throw new FilterParseException("The filter expression nests more than "
                    + EventFilterParser.MAX_DEPTH + " levels deep.", 0, 0)
                    .withCode("filter.error.too.deep", String.valueOf(EventFilterParser.MAX_DEPTH));
        }
        if (++counts.nodes > EventFilterParser.MAX_NODES) {
            throw new FilterParseException("The filter expression has more than "
                    + EventFilterParser.MAX_NODES + " terms.", 0, 0)
                    .withCode("filter.error.too.many.terms", String.valueOf(EventFilterParser.MAX_NODES));
        }
        switch (node) {
            case Predicate.And and -> and.nodes().forEach(child -> walk(child, depth + 1, counts));
            case Predicate.Or or -> or.nodes().forEach(child -> walk(child, depth + 1, counts));
            case Predicate.Not not -> walk(not.node(), depth + 1, counts);
            case Predicate.Comparison c -> {
                walk(c.left(), depth + 1, counts);
                walk(c.right(), depth + 1, counts);
            }
            case Predicate.Like like -> {
                walk(like.left(), depth + 1, counts);
                walk(like.pattern(), depth + 1, counts);
            }
            case Predicate.In in -> {
                walk(in.left(), depth + 1, counts);
                in.values().forEach(value -> walk(value, depth + 1, counts));
            }
            case Predicate.Between between -> {
                walk(between.left(), depth + 1, counts);
                walk(between.low(), depth + 1, counts);
                walk(between.high(), depth + 1, counts);
            }
            case Predicate.IsNull isNull -> walk(isNull.operand(), depth + 1, counts);
            case Predicate.BooleanValue value -> walk(value.operand(), depth + 1, counts);
        }
    }

    private static void walk(Expr node, int depth, Counts counts) {
        if (depth > EventFilterParser.MAX_DEPTH) {
            throw new FilterParseException("The filter expression nests more than "
                    + EventFilterParser.MAX_DEPTH + " levels deep.", 0, 0)
                    .withCode("filter.error.too.deep", String.valueOf(EventFilterParser.MAX_DEPTH));
        }
        if (++counts.nodes > EventFilterParser.MAX_NODES) {
            throw new FilterParseException("The filter expression has more than "
                    + EventFilterParser.MAX_NODES + " terms.", 0, 0)
                    .withCode("filter.error.too.many.terms", String.valueOf(EventFilterParser.MAX_NODES));
        }
        switch (node) {
            case Expr.FunctionCall call -> {
                if (++counts.functionCalls > EventFilterParser.MAX_FUNCTION_CALLS) {
                    throw new FilterParseException("The filter expression calls more than "
                            + EventFilterParser.MAX_FUNCTION_CALLS + " functions.", 0, 0)
                            .withCode("filter.error.too.many.functions",
                                    String.valueOf(EventFilterParser.MAX_FUNCTION_CALLS));
                }
                call.args().forEach(arg -> walk(arg, depth + 1, counts));
            }
            case Expr.Cast cast -> walk(cast.operand(), depth + 1, counts);
            case Expr.ColumnRef ignored -> { }
            case Expr.MetadataRef ignored -> { }
            case Expr.StringLiteral ignored -> { }
            case Expr.NumberLiteral ignored -> { }
            case Expr.BooleanLiteral ignored -> { }
        }
    }
}
