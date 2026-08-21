// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.helpers.text;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The pattern rules the filter contract promises callers.
 *
 * <p>SQL {@code LIKE} has two wildcards, {@code %} (any run) and {@code _} (exactly one character),
 * and this platform's identifiers are made of underscores. So the contract keeps {@code %}, adds
 * {@code *} as a synonym, and takes {@code _} away — a filter for {@code sap_work_orders} must not
 * also return {@code sapXwork_orders}, and a caller has no other way to say which they meant.
 */
class WildcardPatternsTest {

    @ParameterizedTest
    @ValueSource(strings = {"sap_*", "sap_%", "*", "%", "*middle*"})
    void entriesCarryingAWildcardArePatterns(String value) {
        assertTrue(WildcardPatterns.isPattern(value));
    }

    @ParameterizedTest
    @ValueSource(strings = {"sap_work_orders", "plant-a", "rpm_pump_1", "COM-99-PT-1034", "=K1-M3+B02"})
    void entriesWithoutOneAreLiteral(String value) {
        // These take the indexed path instead of a scan, so getting this wrong is a performance
        // cliff as well as a correctness one.
        assertFalse(WildcardPatterns.isPattern(value));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            // both spellings of "any run" reach SQL as %
            "sap_*          | sap\\_%",
            "sap_%          | sap\\_%",
            "*_archive      | %\\_archive",
            "*sap*          | %sap%",
            // no wildcard: still a valid pattern, matching exactly that string
            "sap_work_orders| sap\\_work\\_orders",
            // a backslash the caller wrote is matched literally, not treated as an escape
            "a\\b           | a\\\\b",
    })
    void translationEscapesWhatSqlWouldOtherwiseTreatAsAWildcard(String input, String expected) {
        assertEquals(expected, WildcardPatterns.toSqlLike(input.trim()));
    }

    @Test
    void theBackslashEscapeIsEmittedBeforeTheUnderscoreEscape() {
        // If the underscore were escaped first, the backslash pass would then escape the escape and
        // the pattern would look for a literal backslash followed by any character.
        assertEquals("a\\\\b\\_c", WildcardPatterns.toSqlLike("a\\b_c"));
    }

    @Test
    void nullPassesThrough() {
        assertEquals(null, WildcardPatterns.toSqlLike(null));
        assertFalse(WildcardPatterns.isPattern(null));
    }
}
