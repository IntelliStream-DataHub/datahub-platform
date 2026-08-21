// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.helpers.text;

import net.openhft.hashing.LongHashFunction;

/**
 * The one place label names are canonicalised and hashed, the way {@link ExternalIds} is for
 * external ids.
 *
 * <p>Label names are stored canonical rather than verbatim: {@link TextValidator#toSnakeUpperCased}
 * strips leading digits, upper-cases, and snakes the special characters, so {@code "pump a"},
 * {@code "Pump-A"} and {@code "PUMP_A"} are all the same label. {@code label.hash} is the xx3 hash
 * of that canonical form and carries the unique index ({@code label_hash_key}), so it — not the
 * text column — is what lookups should match on.
 *
 * <p>This class exists because there were two implementations of that rule: {@code Label.setName}
 * canonicalised then hashed inline, and anything wanting to <em>query</em> by label had to
 * reproduce both steps. Reproducing them slightly differently fails silently, exactly as
 * {@link ExternalIds} describes — the filter matches nothing and looks like an empty result rather
 * than a bug.
 */
public final class Labels {

    private Labels() {
    }

    /**
     * The stored form of a label name. Null and blank pass through unchanged, inherited from
     * {@link TextValidator#toSnakeUpperCased}, so a blank still reaches a downstream blank check
     * instead of being silently turned into underscores.
     */
    public static String canonical(String name) {
        return TextValidator.toSnakeUpperCased(name);
    }

    /**
     * Hash a label name for storage in {@code label.hash} and for every lookup against it.
     * Canonicalises first, so a caller filtering on {@code "pump a"} matches the label stored as
     * {@code PUMP_A}.
     *
     * @param name the label name as written by the caller; must not be null
     * @return the hash of its canonical form
     */
    public static long hash(String name) {
        return LongHashFunction.xx3().hashChars(canonical(name));
    }
}
