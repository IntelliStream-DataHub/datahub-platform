// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.dhconsole.chat.llm;

import java.io.Serializable;
import java.util.List;

/**
 * One conversation turn.
 *
 * <p>Tool results are carried on a {@link Role#USER} message, and <strong>all results for a given
 * assistant turn must sit on a single one</strong>. That is the Anthropic message shape; adapters
 * for other providers re-project it (an OpenAI-compatible server wants one {@code role:"tool"}
 * message per result). The loop only ever produces the batched form.
 */
public record LlmMessage(Role role, List<LlmBlock> blocks) implements Serializable {

    public enum Role {USER, ASSISTANT}

    public static LlmMessage user(String text) {
        return new LlmMessage(Role.USER, List.of(new LlmBlock.Text(text)));
    }

    public static LlmMessage assistant(List<LlmBlock> blocks) {
        return new LlmMessage(Role.ASSISTANT, List.copyOf(blocks));
    }

    /** The single batched message carrying every tool result from one assistant turn. */
    public static LlmMessage toolResults(List<LlmBlock> results) {
        return new LlmMessage(Role.USER, List.copyOf(results));
    }

    /** Concatenated prose, ignoring tool blocks. */
    public String text() {
        return blocks.stream()
                .filter(LlmBlock.Text.class::isInstance)
                .map(b -> ((LlmBlock.Text) b).text())
                .reduce("", (a, b) -> a.isEmpty() ? b : a + "\n" + b);
    }

    /** True for an ordinary typed-by-the-user turn — no tool results riding along. */
    public boolean isPlainUserTurn() {
        return role == Role.USER && blocks.stream().allMatch(LlmBlock.Text.class::isInstance);
    }
}
