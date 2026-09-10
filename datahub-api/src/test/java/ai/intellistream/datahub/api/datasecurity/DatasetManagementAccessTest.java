// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.datasecurity;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static ai.intellistream.datahub.api.datasecurity.TestDataSecurity.granting;
import static ai.intellistream.datahub.api.datasecurity.TestDataSecurity.readingAndWritingEverything;
import static ai.intellistream.datahub.api.datasecurity.TestDataSecurity.readingEverything;
import static ai.intellistream.datahub.api.datasecurity.TestDataSecurity.writingEverything;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Managing datasets (create / update / delete of a dataset itself) requires an all-datasets write
 * grant.
 *
 * <p>A dataset is the unit access is granted on, so creating one, renaming it or moving it in the
 * hierarchy changes what existing grants cover. Holding a grant on some datasets must not let a
 * caller mint more, or re-parent one under a tree they can read.
 *
 * <p>This is pinned separately from the orphan rule that currently produces the same answer,
 * because it is a deliberate requirement rather than a side effect of dataset nodes having no
 * {@code data_set_id}. If someone revisits orphan handling, this test should fail rather than the
 * requirement quietly disappearing.
 *
 * <p>The generic {@code /resources} pipeline enforces the same rule explicitly on dataset and
 * policy nodes, whatever their {@code data_set_id} — see {@code ResourceServiceNodeTypeAclTest}
 * for the endpoint-side coverage; this class pins the grant semantics themselves.
 */
class DatasetManagementAccessTest {

    @Test
    void anAllDatasetsWriteGrantAllowsManagement() {
        assertThat(writingEverything().canManageDataSets()).isTrue();
        assertThat(readingAndWritingEverything().canManageDataSets()).isTrue();
    }

    /** The point of the rule: per-dataset grants, however many, never confer it. */
    @Test
    void perDatasetGrantsDoNotAllowManagement() {
        DataSecurity writesManyDatasets = granting(Set.of(1L, 2L, 3L), Set.of(1L, 2L, 3L));

        assertThat(writesManyDatasets.canManageDataSets()).isFalse();
        assertThatThrownBy(writesManyDatasets::assertCanManageDataSets)
                .isInstanceOf(DatasetAccessDeniedException.class);
    }

    /** Read-all is not write-all: browsing every dataset does not confer creating one. */
    @Test
    void readAllDoesNotAllowManagement() {
        assertThat(readingEverything().canManageDataSets()).isFalse();
    }

    @Test
    void noGrantsDoNotAllowManagement() {
        assertThat(TestDataSecurity.grantingNothing().canManageDataSets()).isFalse();
    }

    /**
     * The denial must name the grant needed. The generic message would read "no write permission
     * for data set: null" and send the reader looking for a dataset that was never the point.
     */
    @Test
    void theDenialNamesTheGrantRequired() {
        assertThatThrownBy(() -> granting(Set.of(1L), Set.of(1L)).assertCanManageDataSets())
                .isInstanceOf(DatasetAccessDeniedException.class)
                .hasMessageContaining("/datasets/*/write")
                .hasMessageContaining("all-datasets write grant")
                .hasMessageNotContaining("null");
    }

    @Test
    void allowedManagementDoesNotThrow() {
        writingEverything().assertCanManageDataSets();
    }
}
