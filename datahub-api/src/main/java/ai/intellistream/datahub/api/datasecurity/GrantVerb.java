// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.datasecurity;

import java.util.Map;
import java.util.TreeMap;

/**
 * The verbs the grant grammar knows, platform-wide: one closed vocabulary, so every subject's
 * groups read the same way. A grammar declares the subset that applies to its subject
 * ({@link GrantGrammar#of}); a new verb is a constant here plus that declaration.
 *
 * <p>The wire form is the lowercase name — {@code /datasets/<id>/read} — matched
 * case-insensitively and locale-independently, so {@code /Settings/LLM/READ} grants what
 * {@code /settings/llm/read} does rather than silently nothing.
 *
 * <p>Verbs never imply one another: read does not follow from write, and a future constant
 * follows the same rule unless its facade deliberately decides otherwise.
 */
enum GrantVerb {

    READ,
    WRITE;

    /** Any-case spelling → verb. Locale-independent, unlike {@code toLowerCase()}. */
    private static final Map<String, GrantVerb> BY_SPELLING =
            new TreeMap<>(String.CASE_INSENSITIVE_ORDER);

    static {
        for (GrantVerb verb : values()) {
            BY_SPELLING.put(verb.name(), verb);
        }
    }

    /** The verb this path segment names, or {@code null} if it names none. */
    static GrantVerb fromSpelling(String spelling) {
        return BY_SPELLING.get(spelling);
    }
}
