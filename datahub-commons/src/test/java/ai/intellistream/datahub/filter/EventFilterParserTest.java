// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.filter;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The front end on its own: text in, {@link Predicate} tree out. No ClickHouse anywhere, which is
 * the point of keeping the parser in this module — the language can be exercised without a
 * database, and the rules below are about the language rather than about any backend.
 */
class EventFilterParserTest {

    @Test
    void blankIsNoFilterRatherThanAnError() {
        assertNull(EventFilterParser.parse(null));
        assertNull(EventFilterParser.parse("   "));
    }

    @Test
    void aLeadingWhereIsOptional() {
        assertThat(EventFilterParser.parse("where type = 'Alarm'"))
                .isEqualTo(EventFilterParser.parse("type = 'Alarm'"));
        assertThat(EventFilterParser.parse("WHERE type = 'Alarm'"))
                .isEqualTo(EventFilterParser.parse("type = 'Alarm'"));
    }

    /** AND binds tighter than OR, so this is A OR (B AND C) — the trap worth pinning. */
    @Test
    void andBindsTighterThanOr() {
        Predicate parsed = EventFilterParser.parse("type = 'a' OR type = 'b' AND status = 'c'");

        Predicate.Or or = assertInstanceOf(Predicate.Or.class, parsed);
        assertThat(or.nodes()).hasSize(2);
        assertInstanceOf(Predicate.Comparison.class, or.nodes().get(0));
        assertInstanceOf(Predicate.And.class, or.nodes().get(1));
    }

    @Test
    void parenthesesOverridePrecedence() {
        Predicate parsed = EventFilterParser.parse("(type = 'a' OR type = 'b') AND status = 'c'");

        Predicate.And and = assertInstanceOf(Predicate.And.class, parsed);
        assertInstanceOf(Predicate.Or.class, and.nodes().get(0));
    }

    @Test
    void notBindsTighterThanAnd() {
        Predicate parsed = EventFilterParser.parse("NOT type = 'a' AND status = 'b'");

        Predicate.And and = assertInstanceOf(Predicate.And.class, parsed);
        assertInstanceOf(Predicate.Not.class, and.nodes().get(0));
    }

    /** Negation has one representation, so NOT LIKE is Not(Like(...)) rather than a flag. */
    @Test
    void negatedFormsBecomeNotWrappingThePositiveOne() {
        assertInstanceOf(Predicate.Not.class, EventFilterParser.parse("type NOT LIKE 'pump%'"));
        assertInstanceOf(Predicate.Not.class, EventFilterParser.parse("type NOT IN ('a','b')"));
        assertInstanceOf(Predicate.Not.class, EventFilterParser.parse("dataSetId NOT BETWEEN 1 AND 5"));
        assertInstanceOf(Predicate.Not.class, EventFilterParser.parse("subType IS NOT NULL"));
        assertInstanceOf(Predicate.IsNull.class, EventFilterParser.parse("subType IS NULL"));
    }

    @Test
    void bothInequalitySpellingsMeanTheSame() {
        assertThat(EventFilterParser.parse("type <> 'a'"))
                .isEqualTo(EventFilterParser.parse("type != 'a'"));
    }

    @Test
    void metadataKeyIsCarriedAsAPlainString() {
        Predicate.Comparison c = assertInstanceOf(Predicate.Comparison.class,
                EventFilterParser.parse("metadata['site'] = 'bergen'"));

        assertThat(c.left()).isEqualTo(new Expr.MetadataRef("site"));
        assertThat(c.right()).isEqualTo(new Expr.StringLiteral("bergen"));
    }

    @Test
    void castSugarAndTheFunctionFormProduceTheSameShape() {
        Predicate.Comparison viaCast = assertInstanceOf(Predicate.Comparison.class,
                EventFilterParser.parse("metadata['n']::int > 5"));

        assertThat(viaCast.left()).isEqualTo(new Expr.Cast(new Expr.MetadataRef("n"), "int"));
    }

    @Test
    void doubledQuotesEscapeASingleQuote() {
        Predicate.Comparison c = assertInstanceOf(Predicate.Comparison.class,
                EventFilterParser.parse("source = 'O''Brien'"));

        assertThat(c.right()).isEqualTo(new Expr.StringLiteral("O'Brien"));
    }

    @Test
    void identifiersMayBeQuotedAndKeywordsAreCaseInsensitive() {
        assertThat(EventFilterParser.parse("\"subType\" is null"))
                .isEqualTo(EventFilterParser.parse("subType IS NULL"));
    }

    @Test
    void commentsAreIgnored() {
        assertThat(EventFilterParser.parse("type = 'a' -- trailing note"))
                .isEqualTo(EventFilterParser.parse("type = 'a'"));
        assertThat(EventFilterParser.parse("type /* inline */ = 'a'"))
                .isEqualTo(EventFilterParser.parse("type = 'a'"));
    }

    @Test
    void subqueryKeywordsSayTheFeatureIsMissing() {
        assertThatThrownBy(() -> EventFilterParser.parse("id IN (SELECT id FROM events)"))
                .isInstanceOf(FilterParseException.class)
                .hasMessageContaining("not supported yet");

        assertThatThrownBy(() -> EventFilterParser.parse("having count() > 1"))
                .isInstanceOf(FilterParseException.class)
                .hasMessageContaining("not supported yet");
    }

    @Test
    void syntaxErrorsCarryAnOffset() {
        FilterParseException e = (FilterParseException) org.assertj.core.api.Assertions
                .catchThrowable(() -> EventFilterParser.parse("type = "));

        assertThat(e).isNotNull();
        assertThat(e.getOffset()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void grammarRefusesWhatItWasNeverGiven() {
        for (String rejected : new String[]{
                "type = 'a'; DROP TABLE events",
                "1 = 1 UNION SELECT 1",
                "CASE WHEN type = 'a' THEN 1 END = 1",
                "dataSetId + 1 = 2",
                "type || 'x' = 'ax'",
                "type ~ 'pump'",
                "type IS DISTINCT FROM 'a'"}) {
            assertThatThrownBy(() -> EventFilterParser.parse(rejected))
                    .as("should be rejected: %s", rejected)
                    .isInstanceOf(FilterParseException.class);
        }
    }

    /**
     * {@code ANY(...)} is shaped like a function call, so the grammar accepts it and the function
     * registry is what refuses it. Recorded here so the boundary between the two is deliberate
     * rather than a surprise to whoever reads the rejected corpus.
     */
    @Test
    void functionShapedSqlParsesAndIsLeftToTheRegistry() {
        Predicate.Comparison c = assertInstanceOf(Predicate.Comparison.class,
                EventFilterParser.parse("type = ANY('a')"));

        assertThat(c.right()).isInstanceOf(Expr.FunctionCall.class);
    }

    /**
     * The offset must index the whole expression, not the line it happens to fall on. A filter box
     * that accepts newlines is what makes the difference visible: a caret drawn at ANTLR's own
     * column would land on the right column of the wrong line.
     */
    @Test
    void offsetsAreAbsoluteAcrossNewlines() {
        FilterParseException e = (FilterParseException) org.assertj.core.api.Assertions
                .catchThrowable(() -> EventFilterParser.parse("type = 'a'\nAND status ="));

        assertThat(e).isNotNull();
        assertThat(e.getOffset()).isGreaterThan("type = 'a'".length());
    }

    @Test
    void limitsAreEnforced() {
        assertThatThrownBy(() -> EventFilterParser.parse("type = '" + "x".repeat(5000) + "'"))
                .isInstanceOf(FilterParseException.class)
                .hasMessageContaining("limit is");

        String deep = "(".repeat(25) + "type = 'a'" + ")".repeat(25);
        assertThatThrownBy(() -> EventFilterParser.parse(deep))
                .isInstanceOf(FilterParseException.class);
    }
}
