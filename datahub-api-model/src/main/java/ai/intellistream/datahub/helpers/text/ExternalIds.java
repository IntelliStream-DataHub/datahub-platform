// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.helpers.text;

import net.openhft.hashing.LongHashFunction;

import java.util.Locale;

/**
 * The one place external ids are turned into the derived forms the platform stores and queries.
 *
 * <p>External ids are stored <em>verbatim</em>: what the caller sends is what is read back, byte for
 * byte, so an ISA-5.1 plant tag ({@code COM-99-PT-1034}) or an IEC 81346 reference designation
 * ({@code =K1-M3+B02}) survives a round trip intact. Uniqueness and lookup, however, are
 * case-insensitive. Both of those follow from {@link #hash(String)} lowercasing before hashing, so
 * {@code VAL-01} and {@code val-01} land on the same {@code external_id_hash} value and the existing
 * unique index rejects the second one.
 *
 * <p><strong>Every external-id hash must go through {@link #hash(String)}.</strong> Calling
 * {@code LongHashFunction.xx3().hashChars(externalId)} directly skips the lowercasing, which fails
 * two ways and neither is loud: a lookup silently misses a row that is there, or a duplicate slips
 * past the unique index because it hashed to a different value than its case-variant twin.
 * {@code ExternalIdHashingConventionTest} fails the build on a raw call in an external-id path.
 *
 * <p>Names are not external ids. Some entities hash their <em>name</em> into their own index and
 * deliberately do not use this class.
 */
public final class ExternalIds {

    /**
     * Separator characters folded together by {@link #fold(String)}. Must stay in step with the
     * {@code node_external_id_folded_idx} functional index, which does
     * {@code lower(translate(external_id, '-.:+=', '_____'))} — change one and the guard stops
     * using the index, degrading to a sequential scan on every batch write.
     */
    private static final String FOLD_SEPARATORS = "-.:+=";

    private ExternalIds() {
    }

    /**
     * Hash an external id for storage in {@code external_id_hash} and for every lookup against it.
     *
     * <p>Lowercases first, which is what makes uniqueness and lookup case-insensitive while storage
     * stays verbatim. Existing rows need no backfill: the validator only ever accepted
     * {@code [a-z0-9_]}, so every stored external id is already lowercase and
     * {@code hash(lower(x)) == hash(x)} for all of them.
     *
     * @param externalId the verbatim external id; must not be null
     * @return the hash of its lowercased form
     */
    public static long hash(String externalId) {
        return LongHashFunction.xx3().hashChars(externalId.toLowerCase(Locale.ROOT));
    }

    /**
     * Fold an external id to the form the near-duplicate guard compares on: lowercased, with every
     * separator in {@value #FOLD_SEPARATORS} collapsed to {@code _}.
     *
     * <p>So {@code pump-a-01}, {@code PUMP.A.01} and {@code pump_a_01} all fold to {@code pump_a_01}.
     * They are different identifiers under the uniqueness rule — the guard exists because they are
     * almost certainly meant to be the same one, and a search for either finds only its own.
     *
     * @param externalId the verbatim external id; must not be null
     * @return its folded form
     */
    public static String fold(String externalId) {
        StringBuilder folded = new StringBuilder(externalId.length());
        for (int i = 0; i < externalId.length(); i++) {
            char c = externalId.charAt(i);
            folded.append(FOLD_SEPARATORS.indexOf(c) >= 0 ? '_' : c);
        }
        return folded.toString().toLowerCase(Locale.ROOT);
    }
}
