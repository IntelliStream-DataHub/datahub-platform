// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.dhconsole.chat.llm;

import java.util.List;

/**
 * One assistant response.
 *
 * <p>{@code wantsTools} is the adapter's normalisation of the provider's stop reason — Anthropic's
 * {@code stop_reason: "tool_use"}, an OpenAI-compatible server's {@code finish_reason:
 * "tool_calls"}. The loop branches on this and nothing else.
 */
public record LlmTurn(List<LlmBlock> blocks, boolean wantsTools) {

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
