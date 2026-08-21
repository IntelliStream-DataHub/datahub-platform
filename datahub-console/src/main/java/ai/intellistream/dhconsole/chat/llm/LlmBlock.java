// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.dhconsole.chat.llm;

import java.io.Serializable;
import java.util.Map;

/**
 * One piece of a conversation turn, in provider-neutral form.
 *
 * <p>These types are what the transcript is made of, and the transcript lives in the HTTP session,
 * which Spring Session serialises into Valkey — hence {@link Serializable}. Keeping the stored
 * shape neutral rather than provider-specific is what lets a second {@link LlmClient} read
 * conversations that an earlier one wrote.
 */
public sealed interface LlmBlock extends Serializable {

    /** Prose from either side of the conversation. */
    record Text(String text) implements LlmBlock {
    }

    /** The model asking for a tool to be run. {@code id} correlates it with its result. */
    record ToolUse(String id, String name, Map<String, Object> args) implements LlmBlock {
    }

    /**
     * The outcome handed back to the model. {@code isError} covers both a failed tool and a call
     * we refused to make — either way the model sees it and can adapt.
     */
    record ToolResult(String toolUseId, String content, boolean isError) implements LlmBlock {
    }
}
