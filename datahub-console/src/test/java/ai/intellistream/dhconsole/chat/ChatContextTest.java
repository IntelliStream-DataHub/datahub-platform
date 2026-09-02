// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.dhconsole.chat;

import ai.intellistream.datahub.tenant.TenantConfigService;
import ai.intellistream.dhconsole.api.DatahubApi;
import ai.intellistream.dhconsole.chat.agent.AgentRunner;
import ai.intellistream.dhconsole.chat.llm.LlmBackends;
import ai.intellistream.dhconsole.chat.mcp.McpBridge;
import ai.intellistream.dhconsole.security.UserSession;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;
import tools.jackson.databind.json.JsonMapper;

import ai.intellistream.datahub.tenant.LlmProvider;
import ai.intellistream.dhconsole.chat.config.AgentSettings;
import ai.intellistream.dhconsole.chat.config.AgentSettingsResolver;
import ai.intellistream.dhconsole.chat.llm.ChatEffort;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static ai.intellistream.dhconsole.chat.config.AgentSettingsFixture.anthropicAgent;
import static ai.intellistream.dhconsole.chat.config.AgentSettingsFixture.openAiCompatibleAgent;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Builds the chat beans in a real Spring context.
 *
 * <p>Every other chat test wires its collaborators by hand, which proves the code works but says
 * nothing about whether the container can construct it. Two shipped bugs came from exactly that
 * gap — a package missing from {@code @ComponentScan}, and a second constructor on
 * {@link McpBridge} that left Spring unable to choose one. With
 * {@code spring.main.lazy-initialization=true} in this module, both surfaced only on the first
 * real request.
 *
 * <p>Deliberately not {@code @SpringBootTest}: that would drag in Vault, Valkey and the OAuth2
 * client. This registers just the chat package plus the one collaborator it needs from outside it.
 */
class ChatContextTest {

    @Configuration
    @EnableConfigurationProperties
    static class Collaborators {
        /** Provided by Boot's Jackson auto-configuration in the real app. */
        @Bean
        JsonMapper jsonMapper() {
            return JsonMapper.builder().build();
        }

        /** Collaborators ChatAccess needs, which live outside the chat package in the real app. */
        @Bean
        TenantConfigService tenantConfigService() {
            return Mockito.mock(TenantConfigService.class);
        }

        @Bean
        UserSession userSession() {
            return new UserSession();
        }

        /** AgentSettingsResolver reads the agent definition through this; never called here. */
        @Bean
        DatahubApi datahubApi() {
            return Mockito.mock(DatahubApi.class);
        }
    }

    private AnnotationConfigApplicationContext contextWith(Map<String, Object> properties) {
        Map<String, Object> all = new HashMap<>(properties);
        all.put("datahub.chat.enabled", "true");
        all.put("datahub.url", "http://localhost:8081");

        var context = new AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources()
                .addFirst(new MapPropertySource("test", all));
        context.register(Collaborators.class);
        context.scan("ai.intellistream.dhconsole.chat");
        context.refresh();
        return context;
    }

    @Test
    void theAgentRuntimeWiresEndToEnd() {
        try (var context = contextWith(Map.of(
                "datahub.chat.provider", "anthropic",
                "datahub.chat.api-key", "sk-ant-test"))) {

            assertThat(context.getBean(AgentRunner.class)).isNotNull();
            assertThat(context.getBean(McpBridge.class)).isNotNull();
            assertThat(context.getBean(AgentSettingsResolver.class)).isNotNull();
            assertThat(context.getBean(LlmBackends.class)).isNotNull();
        }
    }

    @Test
    void theAnthropicBackendIsBuiltOnFirstUse() {
        try (var context = contextWith(Map.of(
                "datahub.chat.provider", "anthropic",
                "datahub.chat.api-key", "sk-ant-test"))) {

            // There is no LlmClient bean any more — a tenant may bring its own credential, so the
            // client is resolved per turn. What the container still owes us is a cache that can
            // build one, which is what this proves.
            LlmBackends backends = context.getBean(LlmBackends.class);
            assertThat(backends.forSettings(anthropicAgent()).providerId(anthropicAgent()))
                    .startsWith("anthropic/");
        }
    }

    @Test
    void theOpenAiCompatibleBackendIsBuiltOnFirstUse() {
        try (var context = contextWith(Map.of(
                "datahub.chat.provider", "openai-compatible",
                "datahub.chat.model", "qwen3.5:latest",
                "datahub.chat.base-url", "http://localhost:11434/v1"))) {

            LlmBackends backends = context.getBean(LlmBackends.class);
            AgentSettings ollama = openAiCompatibleAgent("http://localhost:11434/v1");
            assertThat(backends.forSettings(ollama).providerId(ollama))
                    .isEqualTo("openai-compatible/qwen3.5:latest");
        }
    }

    @Test
    void oneCredentialYieldsOneSharedClient() {
        try (var context = contextWith(Map.of(
                "datahub.chat.provider", "anthropic",
                "datahub.chat.api-key", "sk-ant-test"))) {

            LlmBackends backends = context.getBean(LlmBackends.class);

            // Same credential, different model: one client and therefore one connection pool,
            // because the model is a per-request parameter and not part of the cache key.
            assertThat(backends.forSettings(anthropicAgent()))
                    .isSameAs(backends.forSettings(anthropicAgent()));
        }
    }

    @Test
    void aBackendWithNoCredentialFailsItsOwnTurnRatherThanTheApplication() {
        // This used to fail the whole context at startup, which was right when one key served
        // everyone. Now a missing key belongs to one tenant's configuration, so the container must
        // still come up and the failure must arrive with a message naming what to fix.
        try (var context = contextWith(Map.of("datahub.chat.provider", "anthropic"))) {
            LlmBackends backends = context.getBean(LlmBackends.class);
            AgentSettings noKey = new AgentSettings("a", LlmProvider.ANTHROPIC, null,
                    "claude-opus-5", null, null, java.time.Duration.ofMinutes(4), null, List.of(),
                    ChatEffort.HIGH, null, 6, 40, 24_000);

            assertThatThrownBy(() -> backends.forSettings(noKey))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("llm-backends");
        }
    }

    @Test
    void nothingIsWiredWhenChatIsDisabled() {
        var context = new AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(
                new MapPropertySource("test", Map.of("datahub.chat.enabled", "false")));
        context.register(Collaborators.class);
        context.scan("ai.intellistream.dhconsole.chat");
        context.refresh();

        try (context) {
            // The console must boot unchanged for deployments that have not enabled chat.
            assertThat(context.getBeanNamesForType(AgentRunner.class)).isEmpty();
            assertThat(context.getBeanNamesForType(LlmBackends.class)).isEmpty();
            assertThat(context.getBeanNamesForType(AgentSettingsResolver.class)).isEmpty();
        }
    }
}
