// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.dhconsole.chat.config;

import ai.intellistream.dhconsole.chat.llm.AnthropicLlmClient;
import ai.intellistream.dhconsole.chat.llm.LlmClient;
import ai.intellistream.dhconsole.chat.llm.OpenAiCompatibleLlmClient;
import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

/**
 * Wires the configured chat backend.
 *
 * <p>Everything chat-related is gated on {@code datahub.chat.enabled}, which defaults to false, so
 * a deployment with no model configured boots exactly as before.
 *
 * <p>Adding an airgapped backend means one more {@code case} here plus the adapter itself —
 * nothing else in the feature knows which provider is in use.
 */
@Slf4j
@Configuration
@ConditionalOnProperty(prefix = "datahub.chat", name = "enabled", havingValue = "true")
public class ChatConfig {

    @Bean
    public LlmClient llmClient(ChatProperties properties, JsonMapper json) {
        LlmClient client = switch (properties.getProvider()) {
            case ANTHROPIC -> new AnthropicLlmClient(anthropicClient(properties), properties, json);
            case OPENAI_COMPATIBLE -> new OpenAiCompatibleLlmClient(properties, json);
        };
        // Say which backend won. These properties come from Vault, but VaultConfigurationLoader
        // registers its source with addLast, so a stray line in application-dev.properties shadows
        // it silently — and nothing downstream names the provider until a stack trace happens to.
        log.info("Chat backend: {}", client.providerId());
        return client;
    }

    private static AnthropicClient anthropicClient(ChatProperties properties) {
        if (properties.getApiKey() == null || properties.getApiKey().isBlank()) {
            throw new IllegalStateException(
                    "datahub.chat.enabled is true but no API key is configured. Set the llm.api-key "
                            + "field on the intellistream-datahub/datahub-console Vault secret.");
        }
        return AnthropicOkHttpClient.builder().apiKey(properties.getApiKey()).build();
    }
}
