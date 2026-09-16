// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.jpa.domains;

import ai.intellistream.datahub.helpers.text.ExternalIds;
import ai.intellistream.datahub.helpers.text.TextValidator;
import net.openhft.hashing.LongHashFunction;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Files store external ids verbatim and derive identity from {@link ExternalIds#hash}, the same as
 * every other entity since the naming-policy change.
 *
 * <p>Also pins the two claims the migration rests on: that live rows need no rehash, and that
 * tombstones do. If either stops holding, V45/V46 are wrong and this fails rather than leaving it
 * to be discovered in a tenant.
 */
class INodeVerbatimExternalIdTest {

    private static INode withExternalId(String externalId) {
        INode node = new INode();
        node.setExternalId(externalId);
        return node;
    }

    @Test
    void theExternalIdIsStoredExactlyAsGiven() {
        assertThat(withExternalId("COM-99-PT-1034").getExternalId()).isEqualTo("COM-99-PT-1034");
        assertThat(withExternalId("=K1-M3+B02").getExternalId()).isEqualTo("=K1-M3+B02");
    }

    @Test
    void lookupIsCaseInsensitiveButSeparatorSensitive() {
        // Case folds, which is what makes `VAL-01` and `val-01` the same file.
        assertThat(withExternalId("VAL-01").getExternalIdHash())
                .isEqualTo(withExternalId("val-01").getExternalIdHash());
        // Separators do not: `val-01` and `val_01` are different identifiers. The near-duplicate
        // guard exists to warn about that; identity does not silently merge them.
        assertThat(withExternalId("val-01").getExternalIdHash())
                .isNotEqualTo(withExternalId("val_01").getExternalIdHash());
    }

    /**
     * V45 asserts live rows need no backfill. Every stored external id was written by the old slug
     * rewrite, so it is already lowercase, and for those {@code hash(lower(x)) == xx3(x)} — the old
     * stored hash is already what the new code computes.
     */
    @Test
    void migrationClaim_liveRowsAlreadyCarryTheRightHash() {
        for (String preMigration : new String[]{"report_2026_q2", "image_sola_jpg", "a1_b2_c3"}) {
            String slugged = TextValidator.toSnakeLowerCasedAllowStartWithDigits(preMigration);
            assertThat(slugged).as("fixture must already be in the old stored form").isEqualTo(preMigration);
            long oldStoredHash = LongHashFunction.xx3().hashChars(slugged);
            assertThat(ExternalIds.hash(preMigration))
                    .as("live row '%s' must not need a rehash", preMigration)
                    .isEqualTo(oldStoredHash);
        }
    }

    /**
     * V46 exists because tombstones are the exception: markDeleted bypasses setExternalId, so the
     * uppercase DELETED_ prefix survived and was hashed verbatim.
     */
    @Test
    void migrationClaim_tombstonesDoNeedARehash() {
        String tombstone = "DELETED_a1b2c3_report_2026_q2_1758000000000";
        long oldStoredHash = LongHashFunction.xx3().hashChars(tombstone);
        assertThat(ExternalIds.hash(tombstone))
                .as("tombstone hash must change, which is what V46 rewrites")
                .isNotEqualTo(oldStoredHash);
    }

    /**
     * The charset the upload validates against must exclude path separators, because the trash
     * filename is built out of the external id.
     */
    @Test
    void theCharsetKeepsPathSeparatorsOutOfTheTrashFilename() {
        assertThat(TextValidator.validateExternalIdCharset("../etc/passwd")).isFalse();
        assertThat(TextValidator.validateExternalIdCharset("a/b")).isFalse();
        assertThat(TextValidator.validateExternalIdCharset("has space")).isFalse();
        assertThat(TextValidator.validateExternalIdCharset("COM-99-PT-1034")).isTrue();
        assertThat(TextValidator.validateExternalIdCharset("=K1-M3+B02")).isTrue();
    }
}
