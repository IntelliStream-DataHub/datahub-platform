// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.clickhouse.filter;

import ai.intellistream.datahub.filter.EventFilterParser;
import ai.intellistream.datahub.filter.FilterParseException;
import ai.intellistream.datahub.filter.Predicate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The whole surface of the filter language, as data rather than as code.
 *
 * <p>Two files under {@code src/test/resources/filter} say what the language accepts and what it
 * refuses, one expression per line. Keeping them as data is the point: a reviewer can read the
 * language without reading the grammar, and adding a function means adding lines here rather than
 * hoping someone thinks to write a test.
 *
 * <p>Every rejected line carries the reason it must be rejected FOR. Asserting the category is
 * what stops a case passing for the wrong reason — a mistyped field name refused as a syntax
 * error is a failing test, not a passing one, because the caller would get a useless message.
 */
class EventFilterCorpusTest {

    private record Case(String family, String expression) {
        @Override
        public String toString() {
            return family + " | " + expression;
        }
    }

    private static Stream<Case> accepted() {
        return load("/filter/accepted.txt").stream();
    }

    private static Stream<Case> rejected() {
        return load("/filter/rejected.txt").stream();
    }

    private static List<Case> load(String resource) {
        List<Case> cases = new ArrayList<>();
        try (InputStream in = EventFilterCorpusTest.class.getResourceAsStream(resource);
             BufferedReader reader = new BufferedReader(
                     new InputStreamReader(assertPresent(in, resource), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank() || line.startsWith("#")) {
                    continue;
                }
                int separator = line.indexOf('|');
                cases.add(new Case(line.substring(0, separator).trim(),
                        line.substring(separator + 1).trim()
                                .replace("\\n", "\n").replace("\\t", "\t")));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return cases;
    }

    private static InputStream assertPresent(InputStream in, String resource) {
        assertNotNull(in, "missing corpus resource " + resource);
        return in;
    }

    /**
     * Accepted expressions parse and render, and — the property the design rests on — none of the
     * caller's own text survives into the SQL. Values live in the parameter map; the SQL is
     * keywords, physical column names and generated placeholders.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("accepted")
    void acceptedExpressionsRenderWithoutLeaking(Case testCase) {
        Map<String, Object> params = new LinkedHashMap<>();
        String sql;
        try {
            Predicate parsed = EventFilterParser.parse(testCase.expression());
            assertNotNull(parsed, "parsed to nothing: " + testCase.expression());
            sql = new EventFilterRenderer(params, testCase.expression()).render(parsed);
        } catch (FilterParseException e) {
            throw new AssertionError("should have been accepted: " + testCase.expression()
                    + "\n  -> " + e.getMessage(), e);
        }

        assertThat(sql).isNotBlank();
        for (Object value : params.values()) {
            String text = String.valueOf(value);
            // Short values collide with SQL punctuation and keywords by chance; the leak this
            // guards against is a caller's literal being pasted in, which is never one character.
            if (text.length() > 3) {
                assertThat(sql).as("value reached the SQL: %s", text).doesNotContain(text);
            }
        }
    }

    /** Rejected expressions are refused, and refused for the stated reason. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("rejected")
    void rejectedExpressionsAreRefusedForTheStatedReason(Case testCase) {
        Map<String, Object> params = new LinkedHashMap<>();
        FilterParseException thrown = null;
        try {
            Predicate parsed = EventFilterParser.parse(testCase.expression());
            if (parsed != null) {
                new EventFilterRenderer(params, testCase.expression()).render(parsed);
            }
        } catch (FilterParseException e) {
            thrown = e;
        }
        if (thrown == null) {
            fail("should have been rejected (" + testCase.family() + "): " + testCase.expression());
        }
        assertCategory(testCase, thrown);
    }

    private static void assertCategory(Case testCase, FilterParseException e) {
        String message = e.getMessage();
        switch (testCase.family()) {
            case "metadata-converter" -> {
                assertThat(message).as("%s", testCase).contains("Metadata values are text");
                assertThat(e.getSuggestion()).as("%s", testCase).startsWith("to_");
            }
            case "unknown-field", "physical-column" ->
                    assertThat(message).as("%s", testCase).containsAnyOf("Unknown field", "column name");
            case "unknown-function", "banned-function", "clickhouse-spelling" ->
                    assertThat(message).as("%s", testCase)
                            .containsAnyOf("Unknown function", "ClickHouse spelling", "not available");
            case "reserved-subquery" ->
                    assertThat(message).as("%s", testCase).contains("not supported yet");
            case "limit" ->
                    assertThat(message).as("%s", testCase)
                            .containsAnyOf("limit is", "nests more than", "more than");
            case "not-a-condition" ->
                    assertThat(message).as("%s", testCase).containsAnyOf("not a condition", "could not be parsed");
            default -> assertThat(message).as("%s", testCase).isNotBlank();
        }
    }

    /**
     * A repair the parser would itself reject would be worse than no repair, so every
     * {@code suggestedQuery} the corpus produces is fed back through and must be accepted.
     */
    @Test
    void everySuggestedRepairIsItselfValid() {
        List<String> broken = new ArrayList<>();
        for (Case testCase : load("/filter/rejected.txt")) {
            String repaired = repairFor(testCase.expression());
            if (repaired == null) {
                continue;
            }
            try {
                Predicate parsed = EventFilterParser.parse(repaired);
                new EventFilterRenderer(new LinkedHashMap<>(), repaired).render(parsed);
            } catch (RuntimeException e) {
                broken.add(testCase.expression() + "  ->  " + repaired + "  (" + e.getMessage() + ")");
            }
        }
        assertThat(broken).as("suggested repairs that do not themselves parse").isEmpty();
    }

    private static String repairFor(String expression) {
        try {
            Predicate parsed = EventFilterParser.parse(expression);
            if (parsed != null) {
                new EventFilterRenderer(new LinkedHashMap<>(), expression).render(parsed);
            }
            return null;
        } catch (FilterParseException e) {
            return e.getSuggestedQuery();
        }
    }

    /**
     * Every rejection carries a translation key, so the console can say it in the reader's own
     * language. Asserted over the whole corpus rather than per case: a code is easy to forget on a
     * throw site added later, and the failure mode is silent -- the English sentence still shows,
     * so nothing looks broken until someone reads the page in Norwegian.
     */
    @Test
    void everyRejectionCarriesATranslationKey() {
        List<String> missing = new ArrayList<>();
        for (Case testCase : load("/filter/rejected.txt")) {
            try {
                Predicate parsed = EventFilterParser.parse(testCase.expression());
                if (parsed != null) {
                    new EventFilterRenderer(new LinkedHashMap<>(), testCase.expression()).render(parsed);
                }
            } catch (FilterParseException e) {
                if (e.getCode() == null || e.getCode().isBlank()) {
                    missing.add(testCase.expression() + "  ->  " + e.getMessage());
                } else if (!e.getCode().startsWith("filter.error.")) {
                    missing.add(testCase.expression() + "  ->  odd code " + e.getCode());
                }
            }
        }
        assertThat(missing).as("rejections with no translation key").isEmpty();
    }

    /** The counts the corpus is meant to carry, so shrinking it is a deliberate act. */
    @Test
    void theCorpusIsTheSizeItClaims() {
        assertThat(load("/filter/accepted.txt")).hasSizeGreaterThanOrEqualTo(200);
        assertThat(load("/filter/rejected.txt")).hasSizeGreaterThanOrEqualTo(100);
    }
}
