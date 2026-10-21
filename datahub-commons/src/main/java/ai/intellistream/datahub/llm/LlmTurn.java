// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.llm;

import java.util.List;

/**
 * One assistant response.
 *
 * <p>{@code wantsTools} is the adapter's normalisation of the provider's stop reason — Anthropic's
 * {@code stop_reason: "tool_use"}, an OpenAI-compatible server's {@code finish_reason:
 * "tool_calls"}. The loop branches on this and nothing else. {@code stopReason} is the same
 * vocabulary kept for what the loop cannot read off the blocks: an answer that was cut off at
 * the output ceiling looks like an answer, and only the stop reason says it is not.
 */
public record LlmTurn(List<LlmBlock> blocks, boolean wantsTools, StopReason stopReason, Usage usage) {

    /** Why the model stopped, in the three ways a loop cares about plus everything else. */
    public enum StopReason {
        /** A finished answer. */
        END_TURN,
        /** It wants the tool calls in {@link #toolUses()} run. */
        TOOL_USE,
        /** The output ceiling cut it off; the text is incomplete. */
        MAX_TOKENS,
        /** A refusal, a pause, an unknown value: not a finished answer, not a tool request. */
        OTHER
    }

    /**
     * What one call cost, in the provider's own count. Cache figures are Anthropic's; an
     * OpenAI-compatible server reports cached prompt tokens where it has them and zero otherwise.
     */
    public record Usage(long inputTokens, long outputTokens,
                        long cacheReadInputTokens, long cacheCreationInputTokens) {

        public static final Usage NONE = new Usage(0, 0, 0, 0);

        public Usage plus(Usage other) {
            return new Usage(inputTokens + other.inputTokens, outputTokens + other.outputTokens,
                    cacheReadInputTokens + other.cacheReadInputTokens,
                    cacheCreationInputTokens + other.cacheCreationInputTokens);
        }
    }

    public List<LlmBlock.ToolUse> toolUses() {
        return blocks.stream()
                .filter(LlmBlock.ToolUse.class::isInstance)
                .map(LlmBlock.ToolUse.class::cast)
                .toList();
    }

    public String text() {
        return blocks.stream()
                .filter(LlmBlock.Text.class::isInstance)
                .map(b -> ((LlmBlock.Text) b).text())
                .reduce("", (a, b) -> a.isEmpty() ? b : a + "\n" + b);
    }
}
