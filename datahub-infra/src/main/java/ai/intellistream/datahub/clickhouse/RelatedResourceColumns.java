// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.clickhouse;

import ai.intellistream.datahub.models.IdCollection;

import java.util.ArrayList;
import java.util.List;

/**
 * The three denormalized ClickHouse arrays derived from an event's single ordered
 * {@code List<IdCollection> relatedResources}.
 *
 * <p>The API exposes one list; the table stores {@code related_resources_id},
 * {@code related_resources_external_id} and {@code related_resources_external_id_hash} as three
 * parallel arrays so the bloom-filter indexes behind {@code hasAll(...)} keep applying. Splitting
 * and joining in one place is what makes the columns unable to drift apart: they are always
 * derived together from the same ordered list, and always written together.
 */
record RelatedResourceColumns(List<Long> ids, List<String> externalIds, List<Long> externalIdHashes) {

    /** Splits one resolved list into the three index-aligned column arrays. */
    static RelatedResourceColumns from(List<IdCollection> related) {
        if (related == null || related.isEmpty()) {
            return new RelatedResourceColumns(List.of(), List.of(), List.of());
        }
        List<Long> ids = new ArrayList<>(related.size());
        List<String> externalIds = new ArrayList<>(related.size());
        List<Long> hashes = new ArrayList<>(related.size());
        for (IdCollection entry : related) {
            if (entry == null) { continue; }
            // Resolution guarantees both sides are populated. The sentinels only matter if an
            // unresolved entry ever slips through — they keep the three arrays index-aligned
            // rather than letting one array fall short and silently re-pair every entry after it.
            ids.add(entry.getId() == null ? 0L : entry.getId());
            externalIds.add(entry.getExternalId() == null ? "" : entry.getExternalId());
            Long hash = entry.getExternalIdHash();
            hashes.add(hash == null ? 0L : hash);
        }
        return new RelatedResourceColumns(ids, externalIds, hashes);
    }

    /**
     * The inverse: positionally zips the two readable columns back into one list.
     *
     * <p>Rows written by {@link #from} are index-aligned by construction. Rows written before the
     * single-list model existed were built by back-filling each array independently, so their two
     * arrays can differ in length and in ordering. Leftovers are therefore emitted as single-sided
     * entries rather than dropped — an old row reads back as a plausible pairing without losing
     * relations, and without an index-out-of-bounds.
     */
    static List<IdCollection> zip(List<Long> ids, List<String> externalIds) {
        int idCount = ids == null ? 0 : ids.size();
        int externalIdCount = externalIds == null ? 0 : externalIds.size();
        int paired = Math.min(idCount, externalIdCount);

        List<IdCollection> related = new ArrayList<>(Math.max(idCount, externalIdCount));
        for (int i = 0; i < paired; i++) {
            IdCollection entry = new IdCollection();
            Long id = ids.get(i);
            String externalId = externalIds.get(i);
            if (id != null && id != 0L) { entry.setId(id); }
            if (externalId != null && !externalId.isEmpty()) { entry.setExternalId(externalId); }
            if (entry.getId() != null || entry.getExternalId() != null) { related.add(entry); }
        }
        for (int i = paired; i < idCount; i++) {
            Long id = ids.get(i);
            if (id != null && id != 0L) { related.add(IdCollection.createFromId(id)); }
        }
        for (int i = paired; i < externalIdCount; i++) {
            String externalId = externalIds.get(i);
            if (externalId != null && !externalId.isEmpty()) { related.add(IdCollection.createFromExternalId(externalId)); }
        }
        return related;
    }
}
