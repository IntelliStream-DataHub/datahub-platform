// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.dhconsole.chat.config;

import ai.intellistream.datahub.tenant.TenantConfigService;
import ai.intellistream.dhconsole.chat.llm.LlmBackends;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;
import tools.jackson.databind.json.JsonMapper;

/**
 * Wires the chat backend, gated on {@code datahub.chat.enabled} so a deployment with no model
 * configured boots exactly as before.
 *
 * <p>There is no {@code LlmClient} bean: clients are per credential and resolved per turn, so
 * {@link LlmBackends} owns them. A missing key is therefore one tenant's failed turn rather than a
 * context that will not start.
 */
@Slf4j
@Configuration
@ConditionalOnProperty(prefix = "datahub.chat", name = "enabled", havingValue = "true")
public class ChatConfig {

    private final LlmBackends backends;

    public ChatConfig(ChatProperties properties, TenantConfigService tenantConfigService,
                      JsonMapper json) {
        // Worth logging: VaultConfigurationLoader registers its source with addLast, so a stray
        // line in application-dev.properties shadows the Vault value silently.
        log.info("Chat enabled. Default backend: {} ({})",
                properties.getProvider(), properties.getModel());
        this.backends = new LlmBackends(properties, tenantConfigService, json);
    }

    @Bean
    public LlmBackends llmBackends() {
        return backends;
    }

    @Scheduled(fixedDelay = 600_000, initialDelay = 600_000)
    public void evictUnusedChatBackends() {
        backends.evictUnusedBackends();
    }
}
