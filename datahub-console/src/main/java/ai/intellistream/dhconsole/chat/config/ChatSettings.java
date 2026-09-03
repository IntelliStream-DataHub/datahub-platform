// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.dhconsole.chat.config;

import ai.intellistream.datahub.tenant.LlmProvider;
import ai.intellistream.dhconsole.chat.llm.ChatEffort;

import java.time.Duration;

/**
 * The model configuration for one turn: the tenant's own where it has one, the deployment default
 * elsewhere. See {@code ChatSettingsResolver}.
 */
public record ChatSettings(LlmProvider provider,
                           String apiKey,
                           String model,

                           /** OpenAI-compatible only. */
                           String baseUrl,

                           /** OpenAI-compatible only. */
                           String reasoningEffort,

                           Duration turnTimeout,

                           /** A configured roof, or null to let the effort level choose. */
                           Integer maxOutputTokens) {

    /**
     * A configured roof beats the level the user picked, deliberately: it is a statement about
     * money, and truncation is visible where a surprising bill is not.
     */
    public int maxOutputTokensFor(ChatEffort effort) {
        return maxOutputTokens != null ? maxOutputTokens : effort.defaultOutputTokens();
    }

    /**
     * What a cached client may be shared across. The model is absent on purpose — it is a
     * per-request parameter, so tenants on one credential share a connection pool.
     */
    public BackendKey backendKey() {
        return new BackendKey(provider, apiKey, baseUrl);
    }

    /** @see #backendKey() */
    public record BackendKey(LlmProvider provider, String apiKey, String baseUrl) {
    }
}
