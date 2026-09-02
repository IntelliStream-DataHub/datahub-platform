// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.dhconsole.chat.llm;

import ai.intellistream.dhconsole.chat.config.AgentSettings;

import java.util.List;

/**
 * The seam between the agent loop and whichever model is configured.
 *
 * <p>Deliberately one method and provider-neutral types. Two implementations sit behind it, and
 * they are not symmetric on purpose:
 * <ul>
 *   <li>{@link SpringAiAnthropicLlmClient} talks to a service with a published, stable contract,
 *       so it delegates to Spring AI and inherits its handling of effort, adaptive thinking and
 *       prompt-cache breakpoints.</li>
 *   <li>{@link OpenAiCompatibleLlmClient} talks to "OpenAI-compatible" servers, which are a family
 *       rather than a specification, and is hand-written so it can be lenient where a generated
 *       client would throw.</li>
 * </ul>
 * That asymmetry is the reason this interface exists rather than a single vendor SDK being used
 * directly.
 *
 * <p>Implementations translate {@link LlmMessage} into the provider's wire shape — in particular
 * the batched tool-result message, which Anthropic wants as one user message with several
 * {@code tool_result} blocks and OpenAI-compatible servers want as several {@code role:"tool"}
 * messages.
 */
public interface LlmClient {

    /**
     * Run one turn. Implementations must not execute tools themselves — the loop owns that, so
     * that policy and the caller's permissions cannot be bypassed by a provider's convenience
     * feature.
     *
     * @param settings the resolved agent settings for this turn: model, budgets, and the
     *                 credential this client was built for. A parameter rather than client state
     *                 because one client instance is shared by every tenant on the same
     *                 credential, and they do not share a model or a budget.
     * @param effort   how hard to think about this particular message. Also per-call, because the
     *                 user picks it per message and a client is shared across concurrent turns.
     */
    LlmTurn send(AgentSettings settings, String systemPrompt, List<LlmToolDef> tools,
                 List<LlmMessage> messages, ChatEffort effort);

    /** For logging and for the {@code /api/chat} response, so the UI can say what answered. */
    String providerId(AgentSettings settings);
}
