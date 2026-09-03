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
 * <p>There is no {@code LlmClient} bean: every credential belongs to a tenant and is resolved per
 * turn, so {@link LlmBackends} owns them. Enabling chat here only makes it possible — each tenant
 * still has to configure a model before it has one.
 */
@Slf4j
@Configuration
@ConditionalOnProperty(prefix = "datahub.chat", name = "enabled", havingValue = "true")
public class ChatConfig {

    private final LlmBackends backends;

    public ChatConfig(TenantConfigService tenantConfigService, JsonMapper json) {
        log.info("Chat enabled. Each tenant supplies its own model via tenant-config/<org-name>.");
        this.backends = new LlmBackends(tenantConfigService, json);
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
