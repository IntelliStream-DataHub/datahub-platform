// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.filter;

import org.antlr.v4.runtime.tree.ParseTree;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Walks ANTLR's parse tree into a {@link Predicate} tree.
 *
 * <p>Written as plain recursion rather than as a generated visitor because the target has two
 * shapes — {@link Predicate} and {@link Expr} — and a single-typed visitor would spend its time
 * casting between them.
 *
 * <p>ANTLR types stop here. Nothing above this class sees a {@code Context}, so the front end can
 * be replaced (by hand-written recursive descent, by a different generator) without any of the
 * rendering or validation downstream noticing.
 */
final class PredicateBuilder {

    PredicateBuilder() {
    }

    Predicate build(ParseTree tree) {
        EventFilterQueryParser.StatementContext statement =
                (EventFilterQueryParser.StatementContext) tree;
        return expr(statement.expr());
    }

    private Predicate expr(EventFilterQueryParser.ExprContext ctx) {
        return orExpr(ctx.orExpr());
    }

    // A single operand is returned bare rather than as a one-element And/Or. The tree then has no
    // redundant levels, which keeps the depth guard measuring real nesting and makes the rendered
    // SQL free of parentheses nobody asked for.
    private Predicate orExpr(EventFilterQueryParser.OrExprContext ctx) {
        List<Predicate> nodes = new ArrayList<>();
        for (EventFilterQueryParser.AndExprContext child : ctx.andExpr()) {
            nodes.add(andExpr(child));
        }
        return nodes.size() == 1 ? nodes.getFirst() : new Predicate.Or(nodes);
    }

    private Predicate andExpr(EventFilterQueryParser.AndExprContext ctx) {
        List<Predicate> nodes = new ArrayList<>();
        for (EventFilterQueryParser.NotExprContext child : ctx.notExpr()) {
            nodes.add(notExpr(child));
        }
        return nodes.size() == 1 ? nodes.getFirst() : new Predicate.And(nodes);
    }

    private Predicate notExpr(EventFilterQueryParser.NotExprContext ctx) {
        if (ctx instanceof EventFilterQueryParser.NotNodeContext not) {
            return new Predicate.Not(notExpr(not.notExpr()));
        }
        return primary(((EventFilterQueryParser.PrimaryNodeContext) ctx).primary());
    }

    private Predicate primary(EventFilterQueryParser.PrimaryContext ctx) {
        if (ctx instanceof EventFilterQueryParser.GroupNodeContext group) {
            return expr(group.expr());
        }
        return predicate(((EventFilterQueryParser.PredicateNodeContext) ctx).predicate());
    }

    // NOT LIKE, NOT IN, NOT BETWEEN and IS NOT NULL are all wrapped in Predicate.Not around the
    // positive form, so negation has exactly one representation for the renderer to handle.
    private Predicate predicate(EventFilterQueryParser.PredicateContext ctx) {
        if (ctx instanceof EventFilterQueryParser.ComparisonPredicateContext c) {
            return new Predicate.Comparison(operand(c.operand(0)),
                    compareOp(c.comparisonOp()), operand(c.operand(1)));
        }
        if (ctx instanceof EventFilterQueryParser.LikePredicateContext c) {
            Predicate like = new Predicate.Like(operand(c.operand(0)), operand(c.operand(1)), false);
            return c.NOT() != null ? new Predicate.Not(like) : like;
        }
        if (ctx instanceof EventFilterQueryParser.IlikePredicateContext c) {
            Predicate like = new Predicate.Like(operand(c.operand(0)), operand(c.operand(1)), true);
            return c.NOT() != null ? new Predicate.Not(like) : like;
        }
        if (ctx instanceof EventFilterQueryParser.InPredicateContext c) {
            List<Expr> values = new ArrayList<>();
            for (EventFilterQueryParser.LiteralContext literal : c.literal()) {
                values.add(literal(literal));
            }
            Predicate in = new Predicate.In(operand(c.operand()), values);
            return c.NOT() != null ? new Predicate.Not(in) : in;
        }
        if (ctx instanceof EventFilterQueryParser.BetweenPredicateContext c) {
            Predicate between = new Predicate.Between(operand(c.operand()),
                    literal(c.literal(0)), literal(c.literal(1)));
            return c.NOT() != null ? new Predicate.Not(between) : between;
        }
        if (ctx instanceof EventFilterQueryParser.IsNullPredicateContext c) {
            Predicate isNull = new Predicate.IsNull(operand(c.operand()));
            return c.NOT() != null ? new Predicate.Not(isNull) : isNull;
        }
        return new Predicate.BooleanValue(
                operand(((EventFilterQueryParser.BooleanPredicateContext) ctx).operand()));
    }

    private CompareOp compareOp(EventFilterQueryParser.ComparisonOpContext ctx) {
        String text = ctx.getText();
        return switch (text) {
            case "=" -> CompareOp.EQ;
            case "!=", "<>" -> CompareOp.NEQ;
            case "<" -> CompareOp.LT;
            case "<=" -> CompareOp.LTE;
            case ">" -> CompareOp.GT;
            case ">=" -> CompareOp.GTE;
            // Unreachable: comparisonOp only matches the tokens above. Thrown rather than
            // defaulted, because a silent default here would be a wrong query, not a wrong error.
            default -> throw new FilterParseException("Unknown comparison operator '" + text + "'.",
                    ctx.getStart().getCharPositionInLine(), text.length())
                    .withCode("filter.error.unknown.operator", text);
        };
    }

    private Expr operand(EventFilterQueryParser.OperandContext ctx) {
        Expr atom = atom(ctx.atom());
        if (ctx.typeName() == null) {
            return atom;
        }
        return new Expr.Cast(atom, ctx.typeName().getText());
    }

    private Expr atom(EventFilterQueryParser.AtomContext ctx) {
        if (ctx instanceof EventFilterQueryParser.MetadataAtomContext m) {
            return new Expr.MetadataRef(checkPrintable(
                    unquote(m.metadataRef().STRING().getText()), m.metadataRef().STRING().getSymbol()));
        }
        if (ctx instanceof EventFilterQueryParser.FunctionAtomContext f) {
            EventFilterQueryParser.FunctionCallContext call = f.functionCall();
            List<Expr> args = new ArrayList<>();
            for (EventFilterQueryParser.OperandContext arg : call.operand()) {
                args.add(operand(arg));
            }
            return new Expr.FunctionCall(identifier(call.identifier()), args);
        }
        if (ctx instanceof EventFilterQueryParser.ColumnAtomContext col) {
            return new Expr.ColumnRef(identifier(col.identifier()));
        }
        return literal(((EventFilterQueryParser.LiteralAtomContext) ctx).literal());
    }

    private Expr literal(EventFilterQueryParser.LiteralContext ctx) {
        if (ctx.STRING() != null) {
            return new Expr.StringLiteral(
                    checkPrintable(unquote(ctx.STRING().getText()), ctx.STRING().getSymbol()));
        }
        if (ctx.TRUE() != null) {
            return new Expr.BooleanLiteral(true);
        }
        if (ctx.FALSE() != null) {
            return new Expr.BooleanLiteral(false);
        }
        return new Expr.NumberLiteral(new BigDecimal(ctx.NUMBER().getText()));
    }

    private String identifier(EventFilterQueryParser.IdentifierContext ctx) {
        if (ctx.QUOTED_IDENT() != null) {
            String text = ctx.QUOTED_IDENT().getText();
            return text.substring(1, text.length() - 1);
        }
        return ctx.IDENT().getText();
    }

    /**
     * Refuses a control character inside a string value.
     *
     * <p>Not a stylistic rule: ClickHouse's query-parameter encoding terminates a value at a raw
     * tab or newline, so binding one produces "cannot be parsed as String ... only 3 of 10 bytes
     * was parsed" — a 500 for what looks to the caller like an ordinary literal. Refusing it here
     * turns that into a 400 that says what is wrong, and refusing beats silently rewriting the
     * value, which would quietly search for something other than what was asked for.
     */
    private static String checkPrintable(String value, org.antlr.v4.runtime.Token token) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isISOControl(c)) {
                throw new FilterParseException(
                        "A quoted value cannot contain a control character (found "
                                + String.format("U+%04X", (int) c) + ").",
                        token.getCharPositionInLine(), token.getText().length(), null, null,
                        "Tabs and newlines cannot be sent as query parameters; "
                                + "match around them with LIKE instead.")
                        .withCode("filter.error.control.character", String.format("U+%04X", (int) c))
                        .withHelpCode("filter.help.control.character");
            }
        }
        return value;
    }

    /** Strips the surrounding quotes and collapses Postgres's doubled-quote escape. */
    private static String unquote(String quoted) {
        return quoted.substring(1, quoted.length() - 1).replace("''", "'");
    }
}
