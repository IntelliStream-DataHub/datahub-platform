// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.dhconsole.chat.config;

import ai.intellistream.datahub.tenant.TenantConfigService;
import ai.intellistream.dhconsole.chat.llm.LlmBackends;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;
import tools.jackson.databind.json.JsonMapper;

/**
 * Wires the chat backend.
 *
 * <p>Unconditionally, because there is nothing left to condition on: every credential belongs to a
 * tenant and is resolved per turn, so {@link LlmBackends} starts empty and stays empty until a
 * tenant with a configured model has a turn. A deployment that wants no chat configures no tenant
 * and pays for an empty map and a sweep over it.
 *
 * <p>There is no {@code LlmClient} bean for the same reason — which client depends on who is
 * asking, and that is not known until the request.
 */
@Slf4j
@Configuration
public class ChatConfig {

    private final LlmBackends backends;

    public ChatConfig(TenantConfigService tenantConfigService, JsonMapper json) {
        log.info("Chat wired. Each tenant supplies its own model via tenant-config/<org-name>.");
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
