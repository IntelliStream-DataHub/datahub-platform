// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.datasecurity;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The two settings grants. What matters is that neither is inferred from anything else: these
 * decide who may read and rewrite the credential a tenant's assistant is billed on, so a path that
 * merely looks close enough must not grant either.
 */
class SettingsGrantsTest {

    @Test
    void eachGrantComesFromItsOwnGroupAndNothingElse() {
        assertThat(SettingsGrants.from(List.of("/settings/read")))
                .isEqualTo(new SettingsGrants(true, false));
        assertThat(SettingsGrants.from(List.of("/settings/write")))
                .isEqualTo(new SettingsGrants(false, true));
        assertThat(SettingsGrants.from(List.of("/settings/read", "/settings/write")))
                .isEqualTo(SettingsGrants.all());
    }

    @Test
    void writeDoesNotImplyRead() {
        // Deliberate: they are separate memberships, and a deployment that grants one gets one.
        assertThat(SettingsGrants.from(List.of("/settings/write")).canRead()).isFalse();
    }

    @Test
    void datasetGrantsAreNotSettingsGrants() {
        // Both come from the same group list, so the one real hazard is a dataset grant reading as
        // a settings grant. An all-datasets wildcard is the closest thing to "everything" a caller
        // can hold and still must not reach settings.
        assertThat(SettingsGrants.from(List.of("/datasets/*/read", "/datasets/*/write")))
                .isEqualTo(SettingsGrants.none());
        assertThat(SettingsGrants.from(List.of("/datahub/tenant-admin")))
                .isEqualTo(SettingsGrants.none());
    }

    @Test
    void nothingNearbyMatches() {
        assertThat(SettingsGrants.from(List.of(
                "/settings",
                "/settings/read/extra",
                "/Settings/read",
                "settings/read",
                "/settings/*/read",
                "/other/settings/read"))).isEqualTo(SettingsGrants.none());
    }

    @Test
    void noGroupsIsNoGrants() {
        assertThat(SettingsGrants.from(null)).isEqualTo(SettingsGrants.none());
        assertThat(SettingsGrants.from(List.of())).isEqualTo(SettingsGrants.none());
    }
}
