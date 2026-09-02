// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.dhconsole.chat.llm;

import ai.intellistream.dhconsole.chat.config.AgentSettings;

/**
 * Resolves the model client for a turn.
 *
 * <p>One method, because that is genuinely all the loop needs to know about where model clients
 * come from. {@link LlmBackends} is the implementation that caches one per credential; a test
 * supplies a stub without standing up a Vault-backed cache to do it.
 */
@FunctionalInterface
public interface LlmClients {

    /**
     * @throws IllegalStateException if the settings name a backend that cannot be used — a missing
     *                               credential or endpoint. The message names what to configure.
     */
    LlmClient forSettings(AgentSettings settings);
}
