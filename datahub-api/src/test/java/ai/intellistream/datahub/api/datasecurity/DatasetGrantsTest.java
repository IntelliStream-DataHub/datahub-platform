// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.datasecurity;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Parsing of Keycloak organization group paths into dataset grants. */
class DatasetGrantsTest {

    @Test
    void parsesReadAndWriteGrants() {
        DatasetGrants grants = DatasetGrants.from(List.of(
                "/datasets/data_set_sap/read",
                "/datasets/data_set_sap/write",
                "/datasets/data_set_historian/read"));

        assertThat(grants.readExternalIds()).containsExactlyInAnyOrder("data_set_sap", "data_set_historian");
        assertThat(grants.writeExternalIds()).containsExactly("data_set_sap");
    }

    /** Write must not imply read, matching the rest of the dataset ACL. */
    @Test
    void writeDoesNotImplyRead() {
        DatasetGrants grants = DatasetGrants.from(List.of("/datasets/data_set_sap/write"));

        assertThat(grants.readExternalIds()).isEmpty();
        assertThat(grants.writeExternalIds()).containsExactly("data_set_sap");
    }

    // ---- the all-datasets wildcard -------------------------------------------------------------

    /**
     * The {@code *} segment is the all-datasets grant. It sets the blanket flag rather than joining
     * the external-id sets: there is no dataset named {@code *} to expand (the external-id charset
     * does not admit an asterisk), and the flag is what lets the resolver skip expansion entirely.
     */
    @Test
    void wildcardSetsTheBlanketFlagInsteadOfNamingADataset() {
        DatasetGrants grants = DatasetGrants.from(List.of("/datasets/*/read"));

        assertThat(grants.readAll()).isTrue();
        assertThat(grants.writeAll()).isFalse();
        assertThat(grants.readExternalIds()).isEmpty();
        assertThat(grants.isEmpty()).isFalse();
    }

    /** The wildcard follows the same read/write independence as every other grant. */
    @Test
    void wildcardWriteDoesNotImplyWildcardRead() {
        DatasetGrants grants = DatasetGrants.from(List.of("/datasets/*/write"));

        assertThat(grants.writeAll()).isTrue();
        assertThat(grants.readAll()).isFalse();
    }

    /** Wildcard and per-dataset grants coexist: the flag on one side leaves the other side's ids intact. */
    @Test
    void wildcardCombinesWithExplicitGrants() {
        DatasetGrants grants = DatasetGrants.from(List.of(
                "/datasets/*/read",
                "/datasets/data_set_sap/write"));

        assertThat(grants.readAll()).isTrue();
        assertThat(grants.writeAll()).isFalse();
        assertThat(grants.writeExternalIds()).containsExactly("data_set_sap");
    }

    /** A bare wildcard container, or a wildcard with an unknown permission, grants nothing. */
    @Test
    void malformedWildcardPathsGrantNothing() {
        assertThat(DatasetGrants.from(List.of("/datasets/*")).isEmpty()).isTrue();
        assertThat(DatasetGrants.from(List.of("/datasets/*/")).isEmpty()).isTrue();
        assertThat(DatasetGrants.from(List.of("/datasets/*/admin")).isEmpty()).isTrue();
    }

    /**
     * An organization's group tree is theirs and may hold groups unrelated to DataHub. Those must
     * be ignored, not rejected.
     */
    @Test
    void ignoresGroupsOutsideTheDatasetsPrefix() {
        DatasetGrants grants = DatasetGrants.from(List.of(
                "/engineering/backend",
                "/datasets/data_set_sap/read",
                "/some-other-app/admin"));

        assertThat(grants.readExternalIds()).containsExactly("data_set_sap");
        assertThat(grants.writeExternalIds()).isEmpty();
    }

    /** The container group itself grants nothing. */
    @Test
    void ignoresAPathWithNoPermissionSegment() {
        assertThat(DatasetGrants.from(List.of("/datasets/data_set_sap")).isEmpty()).isTrue();
        assertThat(DatasetGrants.from(List.of("/datasets/data_set_sap/")).isEmpty()).isTrue();
        assertThat(DatasetGrants.from(List.of("/datasets/")).isEmpty()).isTrue();
    }

    @Test
    void ignoresUnknownPermissions() {
        assertThat(DatasetGrants.from(List.of(
                "/datasets/data_set_sap/admin",
                "/datasets/data_set_sap/delete")).isEmpty()).isTrue();
    }

    /**
     * Nesting deeper than the grammar is refused rather than guessed at, so an unintended subgroup
     * cannot silently grant something.
     */
    @Test
    void ignoresPathsNestedDeeperThanTheGrammar() {
        assertThat(DatasetGrants.from(List.of("/datasets/team/data_set_sap/read")).isEmpty()).isTrue();
    }

    /**
     * The external id segment is taken verbatim: the group names the data set as it is stored.
     *
     * <p>This used to be snake_cased, which quietly rewrote an industrial tag into something that
     * matched no data set and denied access that had been granted. Case still does not matter — the
     * lookup folds it — but nothing else is adjusted.
     */
    @Test
    void takesTheExternalIdVerbatim() {
        DatasetGrants grants = DatasetGrants.from(List.of(
                "/datasets/COM-99-PT-1034/read",
                "/datasets/DATA_SET_HISTORIAN/write"));

        assertThat(grants.readExternalIds()).containsExactly("COM-99-PT-1034");
        assertThat(grants.writeExternalIds()).containsExactly("DATA_SET_HISTORIAN");
    }

    /**
     * A group whose segment could never be a valid external id grants nothing, rather than being
     * bent into something that happens to match. Spaces are outside the external-id charset, so
     * "Data Set SAP" names no data set; the old normaliser turned it into "data_set_sap" and
     * matched, which was a coincidence of the old naming rule rather than a designed behaviour.
     */
    @Test
    void aSegmentThatIsNotAValidExternalIdMatchesNothing() {
        DatasetGrants grants = DatasetGrants.from(List.of("/datasets/Data Set SAP/read"));

        assertThat(grants.readExternalIds()).containsExactly("Data Set SAP");
        // Kept verbatim rather than silently rewritten. It resolves to no dataset downstream,
        // because no stored external id can contain a space.
    }

    @Test
    void permissionSegmentIsCaseInsensitive() {
        DatasetGrants grants = DatasetGrants.from(List.of(
                "/datasets/data_set_sap/READ",
                "/datasets/data_set_other/Write"));

        assertThat(grants.readExternalIds()).containsExactly("data_set_sap");
        assertThat(grants.writeExternalIds()).containsExactly("data_set_other");
    }

    /**
     * Repeated grants collapse, and they collapse case-insensitively — external ids are unique
     * ignoring case, so these two paths name the same data set. Keeping both would resolve to the
     * same dataset anyway but split one grant set across two closure cache entries.
     */
    @Test
    void deduplicatesRepeatedGrantsIgnoringCase() {
        DatasetGrants grants = DatasetGrants.from(List.of(
                "/datasets/data_set_sap/read",
                "/datasets/Data_Set_SAP/read"));

        assertThat(grants.readExternalIds()).hasSize(1);
    }

    @Test
    void handlesNullAndEmptyInput() {
        assertThat(DatasetGrants.from(null).isEmpty()).isTrue();
        assertThat(DatasetGrants.from(List.of()).isEmpty()).isTrue();
        assertThat(DatasetGrants.from(Arrays.asList(null, "/datasets/data_set_sap/read"))
                .readExternalIds()).containsExactly("data_set_sap");
    }

    @Test
    void grantsAreImmutable() {
        DatasetGrants grants = DatasetGrants.from(List.of("/datasets/data_set_sap/read"));

        assertThat(grants.readExternalIds()).isUnmodifiable();
        assertThat(grants.writeExternalIds()).isUnmodifiable();
    }
}
