// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.dhconsole.chat;

import ai.intellistream.datahub.tenant.TenantConfigService;
import ai.intellistream.dhconsole.chat.agent.ChatService;
import ai.intellistream.dhconsole.chat.llm.LlmClient;
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

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

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
    void theAnthropicProviderWiresEndToEnd() {
        try (var context = contextWith(Map.of(
                "datahub.chat.provider", "anthropic",
                "datahub.chat.api-key", "sk-ant-test"))) {

            assertThat(context.getBean(ChatService.class)).isNotNull();
            assertThat(context.getBean(McpBridge.class)).isNotNull();
            assertThat(context.getBean(LlmClient.class).providerId()).startsWith("anthropic/");
        }
    }

    @Test
    void theOpenAiCompatibleProviderWiresEndToEnd() {
        try (var context = contextWith(Map.of(
                "datahub.chat.provider", "openai-compatible",
                "datahub.chat.model", "qwen3.5:latest",
                "datahub.chat.base-url", "http://localhost:11434/v1"))) {

            assertThat(context.getBean(ChatService.class)).isNotNull();
            assertThat(context.getBean(LlmClient.class).providerId())
                    .isEqualTo("openai-compatible/qwen3.5:latest");
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
            assertThat(context.getBeanNamesForType(ChatService.class)).isEmpty();
            assertThat(context.getBeanNamesForType(LlmClient.class)).isEmpty();
        }
    }
}
