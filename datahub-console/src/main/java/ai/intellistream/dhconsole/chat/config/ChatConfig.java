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
 * Wires the chat backend.
 *
 * <p>Everything chat-related is gated on {@code datahub.chat.enabled}, which defaults to false, so
 * a deployment with no model configured boots exactly as before.
 *
 * <h3>Why there is no {@code LlmClient} bean any more</h3>
 * There used to be one, chosen by a {@code switch} on {@code datahub.chat.provider} and built with
 * the deployment's api key. That is exactly one model client for the whole process, which stopped
 * being expressible the moment a tenant could bring its own provider, key and endpoint. The choice
 * moved from container startup to request time, and {@link LlmBackends} owns the instances — one
 * per credential, shared by every tenant that uses it.
 *
 * <p>The startup credential check moved with it. It used to fail the context if
 * {@code datahub.chat.enabled} was true with no api key, which was right when one key served
 * everyone. Now a missing key is a property of one tenant's configuration, so it fails that
 * tenant's turn with a message naming what to fix and leaves every other tenant working. One broken
 * tenant must not take the application down.
 */
@Slf4j
@Configuration
@ConditionalOnProperty(prefix = "datahub.chat", name = "enabled", havingValue = "true")
public class ChatConfig {

    private final LlmBackends backends;

    public ChatConfig(ChatProperties properties, TenantConfigService tenantConfigService,
                      JsonMapper json) {
        // Say what a tenant that configures nothing will get. These properties come from Vault, but
        // VaultConfigurationLoader registers its source with addLast, so a stray line in
        // application-dev.properties shadows it silently — and nothing downstream names the
        // provider until a stack trace happens to.
        log.info("Chat enabled. Default backend: {} ({})",
                properties.getProvider(), properties.getModel());
        this.backends = new LlmBackends(properties, tenantConfigService, json);
    }

    @Bean
    public LlmBackends llmBackends() {
        return backends;
    }

    /**
     * Drop model clients no tenant configuration names any more, so a rotated credential does not
     * keep a connection pool alive for the life of the process.
     *
     * <p>Ten minutes because this is hygiene rather than correctness: a new credential is a new
     * cache entry, which happens on the next turn whether this ever runs or not.
     */
    @Scheduled(fixedDelay = 600_000, initialDelay = 600_000)
    public void evictUnusedChatBackends() {
        backends.evictUnusedBackends();
    }
}
