// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.filter;

import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.tree.ParseTree;

import java.util.Set;

/**
 * Turns a caller's filter expression into a {@link Predicate} tree.
 *
 * <p><b>The return type is the contract.</b> Nothing on this class returns SQL, so no amount of
 * misuse downstream can turn a caller's text into a query fragment — rendering is a separate step
 * that builds every character from fixed keywords, an allow-list and bound parameters.
 *
 * <p>ANTLR's default error strategy recovers and carries on, which would let a half-understood
 * expression through as if it had parsed. Both the lexer and the parser get a listener that throws
 * on the first problem instead, so the only two outcomes are a complete tree or a rejection.
 *
 * <p>The guards below bound cost as well as shape: a parser is an attack surface for time and
 * memory, not only for injection.
 */
public final class EventFilterParser {

    /** Long enough for a filter a person would write; short enough not to be a payload. */
    public static final int MAX_LENGTH = 4096;
    /** Nesting depth, counted over the boolean tree rather than over parentheses. */
    public static final int MAX_DEPTH = 20;
    /** Total nodes, so breadth is bounded as well as depth. */
    public static final int MAX_NODES = 200;
    /** Function calls in one expression. */
    public static final int MAX_FUNCTION_CALLS = 20;

    private EventFilterParser() {
    }

    /**
     * @param expression the caller's filter, with or without a leading {@code WHERE}
     * @return the parsed tree, or null when the expression is absent or blank — "no advanced
     *         filter" rather than an error, so an SDK that always sends the field is not punished
     * @throws FilterParseException on anything the language does not accept
     */
    public static Predicate parse(String expression) {
        if (expression == null || expression.isBlank()) {
            return null;
        }
        if (expression.length() > MAX_LENGTH) {
            throw new FilterParseException(
                    "The filter expression is " + expression.length() + " characters; the limit is "
                            + MAX_LENGTH + ".", MAX_LENGTH, 0)
                    .withCode("filter.error.too.long",
                            String.valueOf(expression.length()), String.valueOf(MAX_LENGTH));
        }

        // Before lexing, not after: ANTLR's generated parser recurses on nested groups, so a few
        // thousand '(' characters overflow its stack long before any tree exists to measure. The
        // AST guards below cannot see this at all — redundant parentheses add no AST depth.
        checkNestingDepth(expression);

        // The listener is built per parse because it needs the expression: ANTLR reports a
        // position within its LINE, and what a caller can underline is a position within the whole
        // string. The two only agree on a single-line expression.
        BaseErrorListener throwing = throwingListener(expression);

        EventFilterQueryLexer lexer = new EventFilterQueryLexer(CharStreams.fromString(expression));
        lexer.removeErrorListeners();
        lexer.addErrorListener(throwing);

        EventFilterQueryParser parser = new EventFilterQueryParser(new CommonTokenStream(lexer));
        parser.removeErrorListeners();
        parser.addErrorListener(throwing);

        ParseTree tree = parser.statement();
        Predicate predicate = new PredicateBuilder().build(tree);
        Guards.check(predicate);
        return predicate;
    }

    /**
     * Bracket nesting in the raw text, counted before a parser exists to be overflowed.
     *
     * <p>String literals are skipped, so {@code source = '((((('} is a value rather than depth.
     */
    private static void checkNestingDepth(String expression) {
        int depth = 0;
        boolean inString = false;
        for (int i = 0; i < expression.length(); i++) {
            char c = expression.charAt(i);
            if (inString) {
                // '' is an escaped quote and stays inside the literal.
                if (c == '\'' && i + 1 < expression.length() && expression.charAt(i + 1) == '\'') {
                    i++;
                } else if (c == '\'') {
                    inString = false;
                }
                continue;
            }
            switch (c) {
                case '\'' -> inString = true;
                case '(', '[' -> {
                    if (++depth > MAX_DEPTH) {
                        throw new FilterParseException("The filter expression nests more than "
                                + MAX_DEPTH + " levels deep.", i, 1)
                                .withCode("filter.error.too.deep", String.valueOf(MAX_DEPTH));
                    }
                }
                case ')', ']' -> depth--;
                default -> { }
            }
        }
    }

    /**
     * Fails on the first syntax error rather than recovering.
     *
     * <p>ANTLR's default behaviour is to report, insert or delete a token, and continue — which
     * for a query language means a caller can get results for an expression that is not the one
     * they wrote. Rejecting is the only safe reading of "I did not understand this".
     */
    private static final Set<Integer> RESERVED = Set.of(
            EventFilterQueryLexer.SELECT, EventFilterQueryLexer.FROM, EventFilterQueryLexer.GROUP,
            EventFilterQueryLexer.HAVING, EventFilterQueryLexer.EXISTS, EventFilterQueryLexer.UNION,
            EventFilterQueryLexer.JOIN);

    private static BaseErrorListener throwingListener(String expression) {
        return new BaseErrorListener() {
        @Override
        public void syntaxError(Recognizer<?, ?> recognizer, Object offendingSymbol, int line,
                                int charPositionInLine, String msg, RecognitionException e) {
            Token token = offendingSymbol instanceof Token t ? t : null;
            int length = token != null && token.getText() != null ? token.getText().length() : 0;
            int offset = absoluteOffset(expression, line, charPositionInLine);

            // SELECT, FROM, GROUP, HAVING, EXISTS, UNION and JOIN are reserved rather than merely
            // absent, so someone reaching for a subquery is told the feature is missing instead of
            // being told their keyword is an unknown column. See CONSTRAINTS/plan: aggregates over
            // a ReplacingMergeTree read with no FINAL count un-merged duplicates, which has to be
            // answered before HAVING can mean anything.
            if (token != null && RESERVED.contains(token.getType())) {
                throw new FilterParseException("Subqueries and aggregation are not supported yet, "
                        + "so '" + token.getText() + "' cannot be used here.",
                        offset, length, null, null,
                        "This filter takes a single boolean expression over one event.")
                        .withCode("filter.error.subquery.unsupported", token.getText())
                        .withHelpCode("filter.help.single.expression");
            }
            throw new FilterParseException(
                    "The filter expression could not be parsed at position " + offset
                            + ": " + msg + ".", offset, length)
                    .withCode("filter.error.syntax", String.valueOf(offset));
        }
        };
    }

    /**
     * ANTLR's line and column translated into an index into the whole expression.
     *
     * <p>Only these two agree when the expression is one line, and a filter box that accepts
     * newlines makes that the wrong assumption: the caret would land on the right column of the
     * wrong line.
     */
    private static int absoluteOffset(String expression, int line, int charPositionInLine) {
        int index = 0;
        for (int remaining = line - 1; remaining > 0; remaining--) {
            int newline = expression.indexOf('\n', index);
            if (newline < 0) {
                break;
            }
            index = newline + 1;
        }
        return Math.min(index + charPositionInLine, expression.length());
    }
}
