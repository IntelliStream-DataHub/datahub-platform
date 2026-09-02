// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.dhconsole.chat.config;

import ai.intellistream.datahub.tenant.LlmProvider;
import ai.intellistream.dhconsole.chat.llm.ChatEffort;

import java.time.Duration;
import java.util.List;

/**
 * Everything one turn needs, resolved: which model to call, what to tell it, which tools it may
 * be offered, and how much to spend.
 *
 * <p>A flat immutable value rather than three collaborating objects, because it is assembled once
 * per turn on the request thread and then read from several places — the loop, the prompt builder,
 * the model client. Passing it as a value is what keeps any of them from reaching back for
 * request-scoped state on a thread that may no longer be the request's.
 *
 * <p>It is assembled from three layers, narrowest first:
 * <ol>
 *   <li>the <strong>agent</strong> row in the tenant's database — instructions, tools, budgets,</li>
 *   <li>the <strong>backend</strong> it names in the tenant's Vault entry — provider, credential,
 *       model, endpoint,</li>
 *   <li>the <strong>deployment</strong> defaults in {@link ChatProperties}, for anything the first
 *       two left unset.</li>
 * </ol>
 * See {@code AgentSettingsResolver}.
 */
public record AgentSettings(

        /** The agent's external id. For logs, and for saying which agent answered. */
        String agentId,

        LlmProvider provider,
        String apiKey,
        String model,

        /** Only meaningful for {@link LlmProvider#OPENAI_COMPATIBLE}. */
        String baseUrl,

        /** Only meaningful for {@link LlmProvider#OPENAI_COMPATIBLE}. */
        String reasoningEffort,

        Duration turnTimeout,

        /** Appended to the built-in system prompt, never substituted for it. May be null. */
        String instructions,

        /** The agent's explicit tool list, before the caller's permissions narrow it. */
        List<String> toolAllowlist,

        /** Where the effort picker starts. A per-message choice overrides it. */
        ChatEffort defaultEffort,

        /** Configured output roof, or null to let the effort level decide. */
        Integer maxOutputTokens,

        int maxIterations,
        int maxMessages,
        int maxToolResultChars) {

    public AgentSettings {
        toolAllowlist = toolAllowlist == null ? List.of() : List.copyOf(toolAllowlist);
    }

    /**
     * The output ceiling for one call at this effort: what was configured, else what the level
     * wants.
     *
     * <p>The asymmetry is deliberate and predates agents. A configured roof is a statement about
     * money — at Opus pricing one {@code max}-effort turn can run to several calls of 32k output
     * tokens — and the effort picker is a per-message UI control. The setting someone wrote down
     * wins over the one a user clicked; the cost of that is truncation, which is visible, rather
     * than a surprising bill, which is not.
     */
    public int maxOutputTokensFor(ChatEffort effort) {
        return maxOutputTokens != null ? maxOutputTokens : effort.defaultOutputTokens();
    }

    /**
     * What identifies the underlying connection, and therefore what a cached model client may be
     * shared across. Model is deliberately absent: it is a per-request parameter, so two agents on
     * the same credential differing only in model share one client and one connection pool.
     */
    public BackendKey backendKey() {
        return new BackendKey(provider, apiKey, baseUrl);
    }

    /** @see #backendKey() */
    public record BackendKey(LlmProvider provider, String apiKey, String baseUrl) {
    }
}
