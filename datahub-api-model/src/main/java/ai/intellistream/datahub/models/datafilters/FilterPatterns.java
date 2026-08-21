// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.models.datafilters;

import ai.intellistream.datahub.helpers.text.ExternalIds;
import ai.intellistream.datahub.helpers.text.WildcardPatterns;

import java.util.List;

/**
 * Splits a caller's match list into the two things a query can do with it: the literal entries,
 * which an index can resolve, and the wildcard entries, which need a scan.
 *
 * <p>Shared by {@link NodeFilter} and {@code EventFilter}. They cannot share a superclass — events
 * are not nodes — so without this they would each derive the split themselves, and a filter whose
 * derivation drifts does not fail loudly: it silently returns the wrong rows.
 */
public final class FilterPatterns {

    private FilterPatterns() {
    }

    /**
     * The literal entries, hashed the way {@code external_id_hash} is stored. Null in, null out, so
     * a caller can tell "no restriction" from "restricted to nothing"; an empty result means every
     * entry carried a wildcard.
     */
    public static List<Long> exactExternalIdHashes(List<String> externalIds) {
        if (externalIds == null) {
            return null;
        }
        return externalIds.stream()
                .filter(FilterPatterns::isUsable)
                .filter(value -> !WildcardPatterns.isPattern(value))
                .map(ExternalIds::hash)
                .toList();
    }

    /**
     * The literal entries, unhashed and unchanged.
     *
     * <p>For stores whose hash this module cannot compute. Events hash their external id with
     * BLAKE3 over {@code externalId + tenantId}, and the tenant is not something a wire DTO knows,
     * so the query layer takes the raw values and hashes them the same way its writers do.
     */
    public static List<String> literals(List<String> values) {
        if (values == null) {
            return List.of();
        }
        return values.stream()
                .filter(FilterPatterns::isUsable)
                .filter(value -> !WildcardPatterns.isPattern(value))
                .toList();
    }

    /** The wildcard entries only, as SQL {@code LIKE} patterns. */
    public static List<String> wildcardPatterns(List<String> values) {
        return toPatterns(values, false);
    }

    /**
     * Every entry as a SQL {@code LIKE} pattern, wildcard or not. For columns with no hashed
     * counterpart to fall back on, where a literal has to be matched by {@code LIKE} too — which is
     * exact, because {@link WildcardPatterns} escapes what SQL would otherwise read as a wildcard.
     */
    public static List<String> allPatterns(List<String> values) {
        return toPatterns(values, true);
    }

    private static List<String> toPatterns(List<String> values, boolean includeLiterals) {
        if (values == null) {
            return List.of();
        }
        return values.stream()
                .filter(FilterPatterns::isUsable)
                .filter(value -> includeLiterals || WildcardPatterns.isPattern(value))
                .map(WildcardPatterns::toSqlLike)
                .toList();
    }

    private static boolean isUsable(String value) {
        return value != null && !value.isBlank();
    }
}
