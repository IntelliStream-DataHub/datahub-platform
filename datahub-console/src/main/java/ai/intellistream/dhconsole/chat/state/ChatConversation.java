// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.dhconsole.chat.state;

import ai.intellistream.dhconsole.chat.llm.LlmMessage;

import java.io.Serial;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The transcript for one chat panel, held as an HTTP session attribute.
 *
 * <p>Living in the session means Spring Session already persists it to Valkey and already makes it
 * readable from any console instance — no separate store, no TTL of its own, and it disappears
 * when the session does. That only works while the whole turn runs on the request thread; if this
 * ever moves behind a streaming endpoint, the session is committed before the work finishes and
 * the state has to move out with it.
 */
public class ChatConversation implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private final List<LlmMessage> messages = new ArrayList<>();

    public List<LlmMessage> messages() {
        return Collections.unmodifiableList(messages);
    }

    public void append(LlmMessage message) {
        messages.add(message);
    }

    public boolean isEmpty() {
        return messages.isEmpty();
    }

    public void clear() {
        messages.clear();
    }

    /**
     * Drop the oldest exchanges until the transcript fits.
     *
     * <p>Trimming always stops on a plain user turn, so a {@code ToolUse} is never separated from
     * the {@code ToolResult} that answers it and the transcript never opens with orphaned tool
     * results. Both shapes are hard 400s from the Anthropic API rather than soft degradations.
     */
    public void trimTo(int maxMessages) {
        if (messages.size() <= maxMessages) {
            return;
        }
        while (messages.size() > maxMessages && !messages.isEmpty()) {
            messages.removeFirst();
            while (!messages.isEmpty() && !messages.getFirst().isPlainUserTurn()) {
                messages.removeFirst();
            }
        }
    }
}
