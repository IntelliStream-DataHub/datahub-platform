// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.models.policy;

import java.util.Locale;

/**
 * The naming convention a tenant or data set has chosen for its external ids.
 *
 * <p>This is the <em>convention</em>, layered on top of the platform charset floor. The floor
 * applies to resources, data sets and events alike; the preset applies to resources and data sets
 * only. An event external id is the source system's key for the subject the event is about, not a
 * name someone chose, so imposing a house convention on it would be imposing a convention on data
 * the operator does not own.
 */
public enum NamingPreset {

    /**
     * The charset floor and nothing more.
     *
     * <p>For facilities mirroring ISA-5.1 or IEC 81346 tags, which is the product's core promise:
     * "match on the identifiers your operation already maintains". A {@link #SNAKE_CASE} default
     * would have left that promise aspirational, because a default nobody changes <em>is</em> the
     * behaviour. Every id that was valid under the old rule is still valid, verbatim being a
     * superset, so nothing that worked stops working.
     *
     * <p>Accepts everything the charset allows, so on its own it leans entirely on the
     * near-duplicate guard. That is why it is no longer the default: see {@link #QUALIFIED_TAG}.
     */
    VERBATIM_TAG,

    /**
     * Verbatim, plus a floor on how much an id has to say.
     * <strong>The shipped default.</strong>
     *
     * <p>Requires at least {@code NamingPolicy.MIN_QUALIFIED_SEGMENTS} separator-delimited
     * alphanumeric runs, so {@code COM-99-PT-1034} and {@code =K1-M3+B02} pass while
     * {@code pump-1234} does not. The shape is what is checked, never the vocabulary: every
     * separator in the charset counts, and nothing is imposed about case, order or which words
     * appear. A tag naming an area, a type and a sequence is qualified; a bare noun and a number
     * is not, because on a site with more than one pump it does not identify anything on its own.
     *
     * <p><strong>Defaults to warning, not rejecting.</strong> Under-qualification is a judgement
     * about a naming habit rather than a defect in the write, and a short tag can be perfectly
     * correct at a small site. So the write goes through and the finding lands in the steward's
     * queue. That also keeps the promise above intact: nothing that worked stops working, it only
     * becomes visible.
     *
     * <p>The near-duplicate guard defaults to warning too, so nothing in the shipped policy refuses
     * a write. Both rules put their verdict in the findings queue and leave the call to a steward.
     */
    QUALIFIED_TAG,

    /** The old rule, {@code [a-z0-9_]+}. Opt in where a house convention is wanted. */
    SNAKE_CASE,

    /** A caller-supplied regular expression. See {@code NamingPolicy} for the safety limits. */
    PATTERN;

    /**
     * Lenient parse for values arriving from a policy's metadata map. Unknown or absent → the
     * shipped default, so "the default" means one thing whether or not a policy node exists.
     */
    public static NamingPreset parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return QUALIFIED_TAG;
        }
        try {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return QUALIFIED_TAG;
        }
    }

    /** The wire/metadata spelling, e.g. {@code verbatim_tag}. */
    public String wireName() {
        return name().toLowerCase(Locale.ROOT);
    }
}
