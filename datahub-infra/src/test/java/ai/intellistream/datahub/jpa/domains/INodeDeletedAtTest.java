// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.jpa.domains;

import org.junit.jupiter.api.Test;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Soft delete is a timestamp on the row, not a rewrite of the identifier.
 *
 * <p>Deleting used to overload {@code external_id} with three facts at once — the deletion time, the
 * original id, and freeing that id for reuse — because only the {@code is_deleted} boolean had a
 * column. These pin what replaced it.
 */
class INodeDeletedAtTest {

    @Test
    void aNodeIsLiveUntilItCarriesADeletionTime() {
        INode node = new INode();
        node.setExternalId("COM-99-PT-1034");
        assertThat(node.isDeleted()).isFalse();
        assertThat(node.getDeletedAt()).isNull();

        node.setDeletedAt(ZonedDateTime.now(ZoneOffset.UTC));
        assertThat(node.isDeleted()).isTrue();
    }

    @Test
    void deletingDoesNotTouchTheExternalIdOrItsHash() {
        INode node = new INode();
        node.setExternalId("COM-99-PT-1034");
        String externalId = node.getExternalId();
        long hash = node.getExternalIdHash();

        node.setDeletedAt(ZonedDateTime.now(ZoneOffset.UTC));

        assertThat(node.getExternalId()).isEqualTo(externalId);
        assertThat(node.getExternalIdHash()).isEqualTo(hash);
        // ...so restoring is a single write of null, with nothing to recover or recompute.
        node.setDeletedAt(null);
        assertThat(node.isDeleted()).isFalse();
        assertThat(node.getExternalId()).isEqualTo("COM-99-PT-1034");
    }

    /**
     * A live node whose external id merely looks like an old tombstone must not be mistaken for one.
     * That is newly possible: verbatim storage means a caller can send this string, where the old
     * slug rewrite would have lowercased it.
     */
    @Test
    void aLiveIdThatLooksLikeATombstoneIsNotTreatedAsOne() {
        INode node = new INode();
        node.setExternalId("DELETED_a1b2_report_1758000000000");
        assertThat(node.isDeleted())
                .as("only deletedAt decides, never the shape of the id")
                .isFalse();
    }

}
