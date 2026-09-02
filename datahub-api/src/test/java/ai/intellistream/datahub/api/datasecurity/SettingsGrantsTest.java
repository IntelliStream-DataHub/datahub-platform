// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.datasecurity;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reading {@code /settings/read} and {@code /settings/write} out of a caller's organization groups.
 *
 * <p>Small surface, but it is the thing standing between an ordinary user and rewriting what every
 * assistant in the tenant may reach, so each way of getting it wrong is worth stating.
 */
class SettingsGrantsTest {

    @Test
    void readsBothGrants() {
        SettingsGrants grants = SettingsGrants.from(List.of("/settings/read", "/settings/write"));

        assertThat(grants.read()).isTrue();
        assertThat(grants.write()).isTrue();
    }

    @Test
    void writeDoesNotImplyRead() {
        // Matching how dataset grants treat the pair: an automation that pushes configuration in
        // need not be able to read it back.
        SettingsGrants grants = SettingsGrants.from(List.of("/settings/write"));

        assertThat(grants.read()).isFalse();
        assertThat(grants.write()).isTrue();
    }

    @Test
    void readDoesNotImplyWrite() {
        assertThat(SettingsGrants.from(List.of("/settings/read")).write()).isFalse();
    }

    @Test
    void aCallerWithNoGroupsHasNeither() {
        assertThat(SettingsGrants.from(List.of())).isEqualTo(SettingsGrants.none());
        assertThat(SettingsGrants.from(null)).isEqualTo(SettingsGrants.none());
    }

    @Test
    void everythingElseInTheTreeIsIgnored() {
        // An organization's group tree is theirs, and most of it has nothing to do with us.
        SettingsGrants grants = SettingsGrants.from(List.of(
                "/datasets/*/write", "/datahub/tenant-admin", "/engineering/oncall", "/settings"));

        assertThat(grants).isEqualTo(SettingsGrants.none());
    }

    @Test
    void aBareSettingsContainerGrantsNothing() {
        // "/settings" is the parent group somebody has to be in to be in its children. Treating it
        // as a grant would hand write access to anyone who joined the container by accident.
        assertThat(SettingsGrants.from(List.of("/settings"))).isEqualTo(SettingsGrants.none());
    }

    @Test
    void aNestedPathIsNotAGrant() {
        // The grammar is one segment naming the thing and one naming the permission. Anything
        // deeper is somebody else's tree, not a settings grant with extra qualification.
        assertThat(SettingsGrants.from(List.of("/settings/read/all", "/datahub/settings/write")))
                .isEqualTo(SettingsGrants.none());
    }

    @Test
    void caseAndSurroundingSpaceDoNotMatter() {
        // A group tree is typed by hand. Matching exactly is a rule nobody would guess had been
        // applied when their grant silently did nothing.
        assertThat(SettingsGrants.from(List.of("  /Settings/Read  ")).read()).isTrue();
        assertThat(SettingsGrants.from(List.of("/SETTINGS/WRITE")).write()).isTrue();
    }

    @Test
    void aNullPathInTheListIsSkippedRatherThanThrowing() {
        java.util.List<String> withNull = new java.util.ArrayList<>();
        withNull.add(null);
        withNull.add("/settings/read");

        assertThat(SettingsGrants.from(withNull).read()).isTrue();
    }

    @Test
    void adminGetsBoth() {
        // The cross-tenant operator escape hatch, answered from the token so it keeps working when
        // UserInfo does not.
        assertThat(SettingsGrants.all().read()).isTrue();
        assertThat(SettingsGrants.all().write()).isTrue();
    }
}
