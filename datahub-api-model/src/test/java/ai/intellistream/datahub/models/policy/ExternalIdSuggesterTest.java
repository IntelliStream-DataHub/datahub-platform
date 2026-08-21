// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.models.policy;

import ai.intellistream.datahub.helpers.text.ExternalIds;
import ai.intellistream.datahub.helpers.text.TextValidator;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Predicate;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The suggester's two promises: it prefers the entity's name, and it never offers something that
 * would itself be rejected.
 */
class ExternalIdSuggesterTest {

    private static final Predicate<String> NOTHING_TAKEN = folded -> false;

    private static NamingPolicy snakeCase() {
        return new NamingPolicy(1L, "house_rule", NamingPreset.SNAKE_CASE, null,
                PolicyMode.REJECT, PolicyMode.REJECT);
    }

    private static NamingPolicy verbatim() {
        return NamingPolicy.shippedDefault();
    }

    private static NamingPolicy pattern(String regex) {
        return new NamingPolicy(1L, "house_rule", NamingPreset.PATTERN, Pattern.compile(regex),
                PolicyMode.REJECT, PolicyMode.REJECT);
    }

    // --- the name is the preferred source ------------------------------------------------------

    @Test
    void derivesFromTheNameRatherThanTheBrokenId() {
        // The whole point of taking a name. Mangling "VPS!!" gives "vps", which tells nobody
        // anything; the name gives an id a human can read.
        assertEquals("valve_pressure_sensors",
                ExternalIdSuggester.suggest("Valve pressure sensors", "VPS!!", snakeCase(), NOTHING_TAKEN));
    }

    @Test
    void fallsBackToTheOffendingIdWhenThereIsNoName() {
        assertEquals("com_99_pt_1034",
                ExternalIdSuggester.suggest(null, "COM-99-PT-1034", snakeCase(), NOTHING_TAKEN));
        assertEquals("com_99_pt_1034",
                ExternalIdSuggester.suggest("   ", "COM-99-PT-1034", snakeCase(), NOTHING_TAKEN));
    }

    @Test
    void aNameYieldingNothingUsableFallsThroughToTheId() {
        // A name of only punctuation derives to nothing, so the id has to carry it.
        assertEquals("com_99_pt_1034",
                ExternalIdSuggester.suggest("!!!", "COM-99-PT-1034", snakeCase(), NOTHING_TAKEN));
    }

    @Test
    void aNameTooShortToBeAnExternalIdIsSkipped() {
        // "AB" derives to "ab", below the 3-character floor, so it is discarded rather than offered.
        assertEquals("pump_01",
                ExternalIdSuggester.suggest("AB", "PUMP-01", snakeCase(), NOTHING_TAKEN));
    }

    // --- every suggestion is verified ----------------------------------------------------------

    @Test
    void aSuggestionAlwaysSatisfiesThePolicyItIsOfferedFor() {
        // The promise that makes the field trustworthy: a suggestion the caller applies must not
        // bounce straight back with a second rejection.
        NamingPolicy policy = snakeCase();
        for (String input : List.of("Pump-A 01", "COM.99.PT.1034", "  spaced  out  ", "=K1-M3+B02")) {
            String suggestion = ExternalIdSuggester.suggest(input, input, policy, NOTHING_TAKEN);
            if (suggestion == null) {
                continue;
            }
            assertTrue(policy.matchesPreset(suggestion),
                    () -> "suggestion '" + suggestion + "' for input '" + input + "' must match the preset");
            assertTrue(TextValidator.validateExternalIdCharset(suggestion),
                    () -> "suggestion '" + suggestion + "' must satisfy the charset floor");
            assertTrue(suggestion.length() >= 3 && suggestion.length() <= 256,
                    () -> "suggestion '" + suggestion + "' must be within the length bounds");
        }
    }

    @Test
    void neverSuggestsAValueThatIsAlreadyTaken() {
        // Suggesting a taken id would move the caller from a naming rejection to a duplicate
        // rejection, which is not progress.
        Predicate<String> taken = folded -> folded.equals(ExternalIds.fold("valve_pressure_sensors"));

        String suggestion = ExternalIdSuggester.suggest(
                "Valve pressure sensors", "VPS!!", snakeCase(), taken);

        if (suggestion != null) {
            assertNotEquals("valve_pressure_sensors", ExternalIds.fold(suggestion));
        }
    }

    @Test
    void returnsNullRatherThanInventingSomethingUnusable() {
        // Every route exhausted: no name, and an id with nothing salvageable in it. An honest null
        // beats a confident wrong answer.
        assertNull(ExternalIdSuggester.suggest(null, "!!!", snakeCase(), NOTHING_TAKEN));
    }

    @Test
    void neverSuggestsTheInputBack() {
        // Handing back exactly what was submitted is noise, not advice.
        assertNull(ExternalIdSuggester.suggest("pump_a_01", "pump_a_01", verbatim(), NOTHING_TAKEN));
    }

    // --- the pattern preset, which previously got no suggestion at all -------------------------

    @Test
    void suggestsForAPatternPresetByTestingCandidatesAgainstTheRegex() {
        // There is no general way to invert a regex, but there is a perfectly good way to propose
        // candidates and check them — which turns "no suggestion possible" into a useful one.
        NamingPolicy policy = pattern("[a-z0-9]+(?:_[a-z0-9]+)*");

        assertEquals("pump_a_01",
                ExternalIdSuggester.suggest("Pump A 01", "Pump-A 01", policy, NOTHING_TAKEN));
    }

    @Test
    void suggestsAnUpperCaseTagWhenThatIsWhatThePatternWants() {
        NamingPolicy policy = pattern("[A-Z]+(?:_[A-Z0-9]+)*");

        String suggestion = ExternalIdSuggester.suggest(
                "Valve pressure sensors", "vps!", policy, NOTHING_TAKEN);

        assertEquals("VALVE_PRESSURE_SENSORS", suggestion);
        assertTrue(policy.matchesPreset(suggestion));
    }

    @Test
    void suggestsAKebabTagWhenThatIsWhatThePatternWants() {
        NamingPolicy policy = pattern("[a-z0-9]+(?:-[a-z0-9]+)*");

        assertEquals("pump-a-01",
                ExternalIdSuggester.suggest("Pump A 01", "Pump A 01", policy, NOTHING_TAKEN));
    }

    @Test
    void offersNothingWhenNoCandidateCanSatisfyThePattern() {
        NamingPolicy policy = pattern("ZZZ-[0-9]{9}");

        assertNull(ExternalIdSuggester.suggest("Pump A 01", "Pump-A 01", policy, NOTHING_TAKEN));
    }

    // --- verbatim ------------------------------------------------------------------------------

    @Test
    void underVerbatimASuggestionRepairsTheCharsetRatherThanTheConvention() {
        // Verbatim accepts anything the charset floor allows, so the only thing left to fix is a
        // character outside it.
        String suggestion = ExternalIdSuggester.suggest("Pump A 01", "Pump A 01", verbatim(), NOTHING_TAKEN);

        assertNotNull(suggestion);
        assertTrue(TextValidator.validateExternalIdCharset(suggestion));
    }

    @Test
    void industrialTagsSurviveVerbatimUntouched() {
        // Nothing to suggest: it already conforms.
        assertNull(ExternalIdSuggester.suggest("Valve 21", "=K1-M3+B02", verbatim(), NOTHING_TAKEN));
    }
}
