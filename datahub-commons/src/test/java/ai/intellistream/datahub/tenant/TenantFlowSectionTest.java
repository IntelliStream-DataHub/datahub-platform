// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.tenant;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.UnaryOperator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What a flow change leaves in the tenant's secret.
 *
 * <p>Every change rewrites the whole secret, so the rule that matters most fails silently: a change
 * that drops a key it does not own produces a valid write Vault accepts, and the model configuration
 * or a deployment's offline token is simply gone.
 */
class TenantFlowSectionTest {

    private static Map<String, String> existing() {
        Map<String, String> data = new LinkedHashMap<>();
        data.put("llm.provider", "anthropic");
        data.put("llm.api-key", "sk-ant-stored");
        data.put("flow.token.42", "offline-token");
        data.put("flow.secret.sftp-password", "hunter2");
        data.put("flow.client.ingest.client-id", "datahub-ingest");
        data.put("flow.client.ingest.client-secret", "s1");
        return data;
    }

    private static final Map<String, String> UNTOUCHABLE = Map.of(
            "llm.provider", "anthropic",
            "llm.api-key", "sk-ant-stored",
            "flow.token.42", "offline-token");

    @Test
    void everyChangeCarriesTheOtherSectionsAndTheTokensAcross() {
        for (UnaryOperator<Map<String, String>> change : java.util.List.of(
                TenantFlowSection.putSecret("other", "v"),
                TenantFlowSection.removeSecret("sftp-password"),
                TenantFlowSection.putClient("reporting", "datahub-reporting", "s2"),
                TenantFlowSection.removeClient("ingest"))) {
            assertThat(change.apply(existing())).containsAllEntriesOf(UNTOUCHABLE);
        }
    }

    @Test
    void aClientIsWrittenAndRemovedAsAPair() {
        Map<String, String> replaced = TenantFlowSection.putClient("ingest", "new-id", "new-secret")
                .apply(existing());
        assertThat(replaced).containsEntry("flow.client.ingest.client-id", "new-id")
                .containsEntry("flow.client.ingest.client-secret", "new-secret");

        Map<String, String> removed = TenantFlowSection.removeClient("ingest").apply(existing());
        assertThat(removed).doesNotContainKeys("flow.client.ingest.client-id",
                "flow.client.ingest.client-secret");
        assertThat(removed).containsKey("flow.secret.sftp-password");
    }

    @Test
    void removingWhatIsNotThereChangesNothing() {
        assertThat(TenantFlowSection.removeSecret("absent").apply(existing())).isEqualTo(existing());
        assertThat(TenantFlowSection.removeClient("absent").apply(existing())).isEqualTo(existing());
    }

    @Test
    void listingGivesNamesAndClientIdsButNoCredential() {
        Map<String, String> secret = TenantFlowSection.putClient("reporting", "datahub-reporting", "s2")
                .apply(existing());

        assertThat(TenantFlowSection.secretNames(secret)).containsExactly("sftp-password");
        assertThat(TenantFlowSection.clientIds(secret))
                .containsExactly(Map.entry("ingest", "datahub-ingest"),
                        Map.entry("reporting", "datahub-reporting"));
    }

    @Test
    void aNameCannotReachIntoAnotherKey() {
        for (String name : new String[]{"", "Upper", "a.b", "../llm", "-leading", "a".repeat(64), null}) {
            assertThatThrownBy(() -> TenantFlowSection.putSecret(name, "v"))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(TenantFlowSection.putSecret("a".repeat(63), "v").apply(Map.of()))
                .containsKey("flow.secret." + "a".repeat(63));
    }
}
