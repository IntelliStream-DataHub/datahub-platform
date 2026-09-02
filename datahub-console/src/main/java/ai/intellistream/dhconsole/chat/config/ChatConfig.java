// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.dhconsole.chat.config;

import ai.intellistream.datahub.tenant.TenantConfigService;
import ai.intellistream.dhconsole.chat.llm.LlmBackends;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

/**
 * Wires the agent runtime's model layer.
 *
 * <p>Everything chat-related is gated on {@code datahub.chat.enabled}, which defaults to false, so
 * a deployment with no model configured boots exactly as before.
 *
 * <h3>Why there is no {@code LlmClient} bean any more</h3>
 * There used to be one, chosen by a {@code switch} on {@code datahub.chat.provider} and built with
 * the deployment's api key. That is exactly one model client for the whole process, which stopped
 * being expressible the moment a tenant could bring its own provider, key and endpoint. So the
 * choice moved from container startup to request time, and {@link LlmBackends} owns the instances
 * — one per credential, shared by every agent that uses it.
 *
 * <p>The startup credential check moved with it. It used to fail the context if
 * {@code datahub.chat.enabled} was true with no api key, which was the right guard when one key
 * served everyone. Now a missing key is a property of one tenant's configuration, so it fails that
 * tenant's turn with a message naming what to fix, and leaves every other tenant working. One
 * broken tenant must not take the application down.
 */
@Slf4j
@Configuration
@ConditionalOnProperty(prefix = "datahub.chat", name = "enabled", havingValue = "true")
public class ChatConfig {

    @Bean
    public LlmBackends llmBackends(ChatProperties properties,
                                   TenantConfigService tenantConfigService,
                                   JsonMapper json) {
        // Say what a tenant that configures nothing will get. These properties come from Vault,
        // but VaultConfigurationLoader registers its source with addLast, so a stray line in
        // application-dev.properties shadows it silently — and nothing downstream names the
        // provider until a stack trace happens to.
        log.info("Chat enabled. Default backend: {} ({}), default agent: {}",
                properties.getProvider(), properties.getModel(), properties.getAgent());
        return new LlmBackends(properties, tenantConfigService, json);
    }
}
