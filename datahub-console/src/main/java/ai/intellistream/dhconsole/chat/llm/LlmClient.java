// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.dhconsole.chat.llm;

import java.util.List;

/**
 * The seam between the chat loop and whichever model is configured.
 *
 * <p>Deliberately one method and provider-neutral types: adding an airgapped OpenAI-compatible
 * backend later should be one new implementation plus a {@code switch} on
 * {@code datahub.chat.provider}, with no change to the loop, the transcript format, or anything
 * already stored in a user's session.
 *
 * <p>Implementations are responsible for translating {@link LlmMessage} into the provider's wire
 * shape — in particular the batched tool-result message, which Anthropic wants as one user message
 * with several {@code tool_result} blocks and OpenAI-compatible servers want as several
 * {@code role:"tool"} messages.
 */
public interface LlmClient {

    /**
     * Run one turn. Implementations must not execute tools themselves — the loop owns that, so
     * that policy and (later) user confirmation cannot be bypassed by a provider's convenience
     * feature.
     *
     * @param effort how hard to think about this particular message. A per-call argument rather
     *               than client state because the user picks it per message, and because a client
     *               is shared across concurrent turns.
     */
    LlmTurn send(String systemPrompt, List<LlmToolDef> tools, List<LlmMessage> messages,
                 ChatEffort effort);

    /** For logging and for the {@code /api/chat} response, so the UI can say what answered. */
    String providerId();
}
