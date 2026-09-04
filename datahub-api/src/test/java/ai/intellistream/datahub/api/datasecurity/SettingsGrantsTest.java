// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.datasecurity;

import ai.intellistream.datahub.models.tenant.SettingsPermission;
import ai.intellistream.datahub.models.tenant.SettingsScopes;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The settings grants. These decide who may read and rewrite the credential a tenant's assistant is
 * billed on, so the point of these tests is that nothing is inferred: not from a nearby path, not
 * from a dataset grant, and not from one scope to another.
 */
class SettingsGrantsTest {

    private static final String LLM = SettingsScopes.LLM;

    @Test
    void eachPermissionComesFromItsOwnGroup() {
        assertThat(SettingsGrants.from(List.of("/settings/llm/read")).canRead(LLM)).isTrue();
        assertThat(SettingsGrants.from(List.of("/settings/llm/read")).canWrite(LLM)).isFalse();
        assertThat(SettingsGrants.from(List.of("/settings/llm/write")).canWrite(LLM)).isTrue();
        assertThat(SettingsGrants.from(List.of("/settings/llm/write")).canRead(LLM)).isFalse();
    }

    @Test
    void theWildcardCoversEveryScopeIncludingOnesAddedLater() {
        SettingsGrants grants = SettingsGrants.from(List.of("/settings/*/read", "/settings/*/write"));

        assertThat(grants.canRead(LLM)).isTrue();
        assertThat(grants.canWrite(LLM)).isTrue();
        // The reason the wildcard exists: a scope that does not exist yet is already covered, so
        // adding one does not silently strip access from whoever was meant to have all of them.
        assertThat(grants.canRead("something-added-next-year")).isTrue();
    }

    @Test
    void oneScopeGrantsNothingAboutAnother() {
        // The whole reason this is not a flat /settings/read|write pair.
        SettingsGrants grants = SettingsGrants.from(List.of("/settings/llm/write"));

        assertThat(grants.canWrite("billing")).isFalse();
        assertThat(grants.canRead("billing")).isFalse();
    }

    @Test
    void datasetGrantsAreNotSettingsGrants() {
        // Both come from one group list and now share one parser, so the hazard is a dataset grant
        // reading as a settings grant. An all-datasets wildcard is the broadest thing a caller can
        // hold and still must reach nothing here.
        assertThat(SettingsGrants.from(List.of("/datasets/*/read", "/datasets/*/write"))
                .scoped().isEmpty()).isTrue();
        assertThat(SettingsGrants.from(List.of("/datahub/tenant-admin")).scoped().isEmpty()).isTrue();
    }

    @Test
    void nothingNearbyMatches() {
        for (String path : List.of("/settings", "/settings/llm", "/settings/read",
                "/settings/llm/read/extra", "settings/llm/read", "/other/settings/llm/read")) {
            assertThat(SettingsGrants.from(List.of(path)).canRead(LLM))
                    .as("'%s' must not grant read", path).isFalse();
        }
        // "/settings/read" parses as scope "read" with permission... nothing, so it is ignored
        // rather than granting a scope literally named read.
        assertThat(SettingsGrants.from(List.of("/settings/read")).scoped().isEmpty()).isTrue();
    }

    @Test
    void adminHoldsEveryScope() {
        assertThat(SettingsGrants.all().canRead(LLM)).isTrue();
        assertThat(SettingsGrants.all().canWrite("anything")).isTrue();
    }

    @Test
    void byScopeResolvesTheWildcardSoAClientNeverHasTo() {
        assertThat(SettingsGrants.from(List.of("/settings/*/read")).byScope())
                .containsEntry(LLM, new SettingsPermission(true, false));
        assertThat(SettingsGrants.none().byScope())
                .containsEntry(LLM, SettingsPermission.NONE);
        assertThat(SettingsGrants.none().byScope().keySet())
                .containsExactlyElementsOf(SettingsScopes.ALL);
    }

    @Test
    void noGroupsIsNoGrants() {
        assertThat(SettingsGrants.from(null).scoped().isEmpty()).isTrue();
        assertThat(SettingsGrants.from(List.of()).scoped().isEmpty()).isTrue();
    }
}
