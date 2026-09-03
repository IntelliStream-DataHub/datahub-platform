// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.dhconsole.chat.config;

import ai.intellistream.datahub.tenant.LlmProvider;
import ai.intellistream.dhconsole.chat.llm.ChatEffort;

import java.time.Duration;

/**
 * Everything one turn needs about the model it runs on: which model, on whose credential, and how
 * much it may spend getting there. The model comes only from the tenant; the rest is the tenant's
 * where it said something and the deployment default where it did not.
 *
 * @see ChatSettingsResolver
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
                           Integer maxOutputTokens,

                           /** Where the panel's picker starts; users override it per message. */
                           ChatEffort defaultEffort,

                           /** Ceiling on model to tool to model round trips in one turn. */
                           int maxIterations,

                           /** Appended to the built-in system prompt, never replacing it. */
                           String instructions) {

    /**
     * A configured roof beats the level the user picked: the roof is written down once and the
     * picker is clicked per message, so the roof is the more considered of the two. Leaving it
     * unset is a real option — the effort level then chooses, and max effort costs what it costs.
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
