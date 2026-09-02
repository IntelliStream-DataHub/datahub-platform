// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.dhconsole.chat.llm;

import ai.intellistream.datahub.tenant.LlmProvider;
import ai.intellistream.datahub.tenant.Tenant;
import ai.intellistream.datahub.tenant.TenantConfigService;
import ai.intellistream.dhconsole.chat.config.ChatSettings;
import ai.intellistream.dhconsole.chat.config.ChatProperties;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * The per-credential client cache.
 *
 * <p>Two properties matter and pull against each other: tenants sharing a credential must share
 * one client, because the connection pool is the expensive part; and a tenant with its own
 * credential must never reach another tenant's. Both are decided entirely by what goes into the
 * cache key, which is what these tests pin.
 */
class LlmBackendsTest {

    private final JsonMapper json = JsonMapper.builder().build();
    private final TenantConfigService tenants = mock(TenantConfigService.class);

    private LlmBackends backends(ChatProperties properties) {
        tenants.cachedTenants = new ConcurrentHashMap<>();
        return new LlmBackends(properties, tenants, json);
    }

    private LlmBackends backends() {
        ChatProperties properties = new ChatProperties();
        properties.setProvider(LlmProvider.ANTHROPIC);
        properties.setApiKey("deployment-key");
        return backends(properties);
    }

    private static ChatSettings anthropic(String apiKey, String model) {
        return new ChatSettings(LlmProvider.ANTHROPIC, apiKey, model, null, null,
                Duration.ofMinutes(4), null);
    }

    private static ChatSettings openAiCompatible(String baseUrl) {
        return new ChatSettings(LlmProvider.OPENAI_COMPATIBLE, null, "qwen3-32b", baseUrl, null,
                Duration.ofMinutes(4), null);
    }

    @Test
    void oneCredentialYieldsOneClientHoweverManyAgentsUseIt() {
        LlmBackends backends = backends();

        assertThat(backends.forSettings(anthropic("key-a", "claude-opus-5")))
                .isSameAs(backends.forSettings(anthropic("key-a", "claude-opus-5")));
        assertThat(backends.size()).isEqualTo(1);
    }

    @Test
    void twoAgentsOnTheSameCredentialShareAClientEvenOnDifferentModels() {
        // The model is a per-request parameter, so it is deliberately not part of the key. Keying
        // on it would multiply connection pools for no benefit.
        LlmBackends backends = backends();

        assertThat(backends.forSettings(anthropic("key-a", "claude-opus-5")))
                .isSameAs(backends.forSettings(anthropic("key-a", "claude-sonnet-5")));
        assertThat(backends.size()).isEqualTo(1);
    }

    @Test
    void differentCredentialsNeverShareAClient() {
        LlmBackends backends = backends();

        assertThat(backends.forSettings(anthropic("key-a", "claude-opus-5")))
                .isNotSameAs(backends.forSettings(anthropic("key-b", "claude-opus-5")));
        assertThat(backends.size()).isEqualTo(2);
    }

    @Test
    void aRotatedKeyIsANewEntryWithNoInvalidationStepToForget() {
        LlmBackends backends = backends();
        LlmClient before = backends.forSettings(anthropic("old-key", "claude-opus-5"));

        assertThat(backends.forSettings(anthropic("new-key", "claude-opus-5"))).isNotSameAs(before);
    }

    @Test
    void differentEndpointsNeverShareAClient() {
        LlmBackends backends = backends();

        assertThat(backends.forSettings(openAiCompatible("http://a:8000/v1")))
                .isNotSameAs(backends.forSettings(openAiCompatible("http://b:8000/v1")));
    }

    @Test
    void anAnthropicBackendWithNoKeySaysWhatToConfigure() {
        // Whoever hits this cannot fix it from the UI, so the message is aimed at the operator and
        // names both places the key can live.
        assertThatThrownBy(() -> backends().forSettings(anthropic(null, "claude-opus-5")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("tenant-llm")
                .hasMessageContaining("llm.api-key");
    }

    @Test
    void anOpenAiCompatibleBackendWithNoEndpointSaysWhatToConfigure() {
        assertThatThrownBy(() -> backends().forSettings(openAiCompatible(null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("base URL");
    }

    @Test
    void evictionKeepsWhatTheDeploymentDefaultNames() {
        ChatProperties properties = new ChatProperties();
        properties.setProvider(LlmProvider.ANTHROPIC);
        properties.setApiKey("deployment-key");
        LlmBackends backends = backends(properties);

        backends.forSettings(anthropic("deployment-key", "claude-opus-5"));
        backends.evictUnusedBackends();

        // A deployment with no tenant backends at all still needs its own client after a sweep.
        assertThat(backends.size()).isEqualTo(1);
    }

    @Test
    void evictionKeepsWhatATenantStillNames() {
        ChatProperties properties = new ChatProperties();
        properties.setProvider(LlmProvider.ANTHROPIC);
        properties.setApiKey("deployment-key");
        LlmBackends backends = backends(properties);
        tenants.cachedTenants.put("t1", tenantWithKey("tenant-key"));

        backends.forSettings(anthropic("tenant-key", "claude-opus-5"));
        backends.evictUnusedBackends();

        assertThat(backends.size()).isEqualTo(1);
    }

    @Test
    void evictionDropsACredentialNothingNamesAnyMore() {
        LlmBackends backends = backends();
        backends.forSettings(anthropic("rotated-away", "claude-opus-5"));
        assertThat(backends.size()).isEqualTo(1);

        // No tenant and no deployment default names it now, so the connection pool it holds is
        // dead weight for the life of the process unless something drops it.
        backends.evictUnusedBackends();

        assertThat(backends.size()).isZero();
    }

    private static Tenant tenantWithKey(String apiKey) {
        Tenant tenant = new Tenant();
        tenant.setOrganizationId("t1");
        var backend = new ai.intellistream.datahub.tenant.TenantLlm();
        backend.setProvider(LlmProvider.ANTHROPIC);
        backend.setApiKey(apiKey);
        tenant.setLlm(backend);
        return tenant;
    }
}
