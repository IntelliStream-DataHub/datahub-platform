// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.dhconsole.chat.config;

import ai.intellistream.datahub.tenant.LlmProvider;
import ai.intellistream.dhconsole.chat.llm.ChatEffort;

import java.time.Duration;

/**
 * The model configuration for one turn, resolved.
 *
 * <p>An immutable value rather than injected configuration, because it now differs per tenant and
 * is assembled on the request thread. Passing it as a value is what keeps the loop and the model
 * clients from reaching back for request-scoped state on a thread that may no longer be the
 * request's.
 *
 * <p>Assembled from two layers: the tenant's own entry where it has one, and the deployment-wide
 * {@link ChatProperties} for everything it leaves unset. See {@code ChatSettingsResolver}.
 */
public record ChatSettings(LlmProvider provider,
                           String apiKey,
                           String model,

                           /** Only meaningful for {@link LlmProvider#OPENAI_COMPATIBLE}. */
                           String baseUrl,

                           /** Only meaningful for {@link LlmProvider#OPENAI_COMPATIBLE}. */
                           String reasoningEffort,

                           Duration turnTimeout,

                           /** Configured output roof, or null to let the effort level decide. */
                           Integer maxOutputTokens) {

    /**
     * The output ceiling for one call at this effort: what was configured, else what the level
     * wants.
     *
     * <p>The asymmetry is deliberate. A configured roof is a statement about money — at Opus
     * pricing one {@code max}-effort turn can run to several large calls — and the effort picker is
     * a per-message UI control. The setting someone wrote down wins over the one a user clicked;
     * the cost of that is truncation, which is visible, rather than a surprising bill, which is not.
     */
    public int maxOutputTokensFor(ChatEffort effort) {
        return maxOutputTokens != null ? maxOutputTokens : effort.defaultOutputTokens();
    }

    /**
     * What identifies the underlying connection, and therefore what a cached client may be shared
     * across. The model is deliberately absent: it is a per-request parameter, so two tenants on
     * the same credential share one client and one connection pool.
     */
    public BackendKey backendKey() {
        return new BackendKey(provider, apiKey, baseUrl);
    }

    /** @see #backendKey() */
    public record BackendKey(LlmProvider provider, String apiKey, String baseUrl) {
    }
}
