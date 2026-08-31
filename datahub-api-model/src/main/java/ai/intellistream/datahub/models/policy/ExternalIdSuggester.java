// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.models.policy;

import ai.intellistream.datahub.helpers.text.ExternalIds;
import ai.intellistream.datahub.helpers.text.TextValidator;
import ai.intellistream.datahub.models.validation.ExternalIdRules;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Proposes a conforming external id when a policy rejects or warns about one.
 *
 * <h2>It works from the entity's name</h2>
 * The name is the thing a human actually chose; the external id is a derived identifier, and when it
 * is wrong the name is usually the better source to derive a new one from. A resource named
 * {@code "Valve pressure sensors"} whose external id is {@code "VPS!!"} should be offered
 * {@code valve_pressure_sensors}, which is meaningful — not {@code vps}, which is what mangling the
 * broken id gives you. This is the same derivation the console already performs when you type a name
 * and leave the id blank, so a suggestion here matches what the UI would have proposed anyway.
 *
 * <p>The offending external id is the fallback, used when there is no name or when the name yields
 * nothing usable. Both are tried, in that order.
 *
 * <h2>Every suggestion is verified before it is offered</h2>
 * A suggestion that would itself be rejected is worse than no suggestion: it costs the caller a
 * round trip and teaches them not to trust the field. So each candidate must pass the charset floor,
 * the length bounds, <em>and</em> the policy's own preset, and must not collide with an id that is
 * already taken — either stored, or claimed by an earlier item in the same batch. Candidates that
 * fail any of those are discarded, and if none survives the answer is honestly {@code null}.
 *
 * <p>Nothing here mutates what the caller sent. A suggestion is offered for them to accept; silently
 * rewriting an external id is the behaviour this whole change removed.
 */
public final class ExternalIdSuggester {

    private ExternalIdSuggester() {
    }

    /**
     * A conforming alternative, or null when none can be derived.
     *
     * @param name           the entity's name, the preferred source. May be null
     * @param offendingId    the external id the policy objected to. May be null
     * @param policy         the policy the suggestion must satisfy
     * @param foldedIsTaken  whether a folded value is already claimed — by a stored id or by an
     *                       earlier item in this batch. Suggesting a taken id would just move the
     *                       caller from one rejection to another
     */
    public static String suggest(String name, String offendingId, NamingPolicy policy,
                                 Predicate<String> foldedIsTaken) {
        // Nothing to suggest when the submitted value is already fine. Reached in two ways: a
        // caller of this class that has not checked, and the near-duplicate guard — which fires on
        // an id that satisfies the preset perfectly well and is only objectionable because
        // something similar exists. In the second case the value IS taken, so we fall through and
        // look for an alternative.
        if (offendingId != null && isUsable(offendingId, policy) && !isTaken(offendingId, foldedIsTaken)) {
            return null;
        }

        for (String candidate : candidates(name, offendingId, policy)) {
            if (candidate.equals(offendingId)) {
                // Handing back exactly what was submitted is noise, not advice.
                continue;
            }
            if (!isUsable(candidate, policy)) {
                continue;
            }
            if (isTaken(candidate, foldedIsTaken)) {
                continue;
            }
            return candidate;
        }
        return null;
    }

    private static boolean isTaken(String candidate, Predicate<String> foldedIsTaken) {
        return foldedIsTaken != null && foldedIsTaken.test(ExternalIds.fold(candidate));
    }

    /**
     * The candidate ladder, most meaningful first.
     *
     * <p>Ordered rather than scored: the first candidate that survives verification wins, so the
     * order <em>is</em> the preference. Name-derived forms come first because they carry meaning;
     * transformations of the broken id come after, because they preserve whatever the caller was
     * trying to express even when it cannot be read.
     *
     * <p>A {@link LinkedHashSet} because several strategies collapse to the same string on ordinary
     * input — an already-lowercase id is its own {@code lower()} — and trying it twice is wasted work.
     */
    private static List<String> candidates(String name, String offendingId, NamingPolicy policy) {
        Set<String> ordered = new LinkedHashSet<>();
        boolean tagShapes = policy.preset() == NamingPreset.PATTERN;

        // 1. EVERY form derived from the name, before any form derived from the id.
        //    The grouping matters more than the order within each group. Interleaving them lets a
        //    lucky transformation of a broken id beat a meaningful one from the name — a resource
        //    named "Valve pressure sensors" with the id "vps!" would be offered "VPS", which is
        //    exactly the uninformative answer taking a name was meant to avoid.
        addIfPresent(ordered, snakeCase(name));
        if (tagShapes) {
            addIfPresent(ordered, upperSnake(name));
            addIfPresent(ordered, kebab(name));
            addIfPresent(ordered, upper(kebab(name)));
        }

        // 2. Then the id, preserving as much of what the caller wrote as each rule allows.
        addIfPresent(ordered, snakeCase(offendingId));
        addIfPresent(ordered, sanitize(offendingId));
        addIfPresent(ordered, lower(sanitize(offendingId)));
        if (tagShapes) {
            addIfPresent(ordered, upperSnake(offendingId));
            addIfPresent(ordered, kebab(offendingId));
            addIfPresent(ordered, upper(kebab(offendingId)));
            addIfPresent(ordered, upper(sanitize(offendingId)));
        }

        // Tag-shaped forms are generated only for `pattern`, where a house convention might
        // genuinely want them. Under snake_case they would fail verification and be discarded
        // anyway; under verbatim they would *pass* and win, so an id whose only fault was a stray
        // space would come back needlessly upper-cased.
        return new ArrayList<>(ordered);
    }

    /** Charset, length and the policy's own preset. All three, or it is not a suggestion. */
    private static boolean isUsable(String candidate, NamingPolicy policy) {
        return candidate.length() >= ExternalIdRules.MIN_LENGTH
                && candidate.length() <= ExternalIdRules.MAX_LENGTH
                && TextValidator.validateExternalIdCharset(candidate)
                && policy.matchesPreset(candidate);
    }

    private static void addIfPresent(Set<String> target, String candidate) {
        if (candidate != null && !candidate.isBlank()) {
            target.add(candidate);
        }
    }

    /**
     * The console's derive-from-name rule, plus the cleanup that makes the result actually satisfy
     * {@code snake_case}.
     *
     * <p>The raw derivation folds every non-word character to {@code _}, so a name with punctuation
     * or leading/trailing spaces produces {@code __foo__} or {@code a__b} — both of which the
     * snake_case rule rejects, since a separator must sit <em>between</em> two runs. Trimming and
     * collapsing here is what stops the suggester proposing something it would then reject itself.
     */
    private static String snakeCase(String source) {
        if (source == null || source.isBlank()) {
            return null;
        }
        String derived = TextValidator.toSnakeLowerCasedAllowStartWithDigits(source);
        if (derived == null) {
            return null;
        }
        derived = derived.replaceAll("_+", "_");
        derived = TextValidator.trimUnderscores(derived);
        return derived.isBlank() ? null : derived;
    }

    /** snake_case, upper-cased: {@code VALVE_PRESSURE_SENSORS}. */
    private static String upperSnake(String source) {
        String snake = snakeCase(source);
        return snake == null ? null : snake.toUpperCase(Locale.ROOT);
    }

    /** snake_case with hyphens, for tag conventions: {@code valve-pressure-sensors}. */
    private static String kebab(String source) {
        String snake = snakeCase(source);
        return snake == null ? null : snake.replace('_', '-');
    }

    /**
     * Drop every character outside the charset floor, keeping the rest exactly as it is.
     *
     * <p>Deliberately drops rather than substitutes. Turning a space into {@code _} invents a
     * separator the caller did not write, which is how {@code P-101} and {@code P.101} used to
     * collide on {@code p_101}; removing it keeps the suggestion a strict subset of what was sent.
     */
    private static String sanitize(String source) {
        if (source == null || source.isBlank()) {
            return null;
        }
        StringBuilder kept = new StringBuilder(source.length());
        for (int i = 0; i < source.length(); i++) {
            char c = source.charAt(i);
            boolean inCharset = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9')
                    || c == '.' || c == '_' || c == ':' || c == '+' || c == '=' || c == '-';
            if (inCharset) {
                kept.append(c);
            }
        }
        return kept.isEmpty() ? null : kept.toString();
    }

    private static String lower(String source) {
        return source == null ? null : source.toLowerCase(Locale.ROOT);
    }

    private static String upper(String source) {
        return source == null ? null : source.toUpperCase(Locale.ROOT);
    }
}
