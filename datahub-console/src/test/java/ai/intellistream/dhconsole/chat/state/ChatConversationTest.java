// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.dhconsole.chat.state;

import ai.intellistream.dhconsole.chat.llm.LlmBlock;
import ai.intellistream.dhconsole.chat.llm.LlmMessage;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ChatConversationTest {

    /** One user question, an assistant tool call, its result, and a final answer. */
    private static void appendExchange(ChatConversation conversation, String question) {
        conversation.append(LlmMessage.user(question));
        conversation.append(LlmMessage.assistant(List.of(new LlmBlock.ToolUse("t", "dataset_list", Map.of()))));
        conversation.append(LlmMessage.toolResults(List.of(new LlmBlock.ToolResult("t", "{}", false))));
        conversation.append(LlmMessage.assistant(List.of(new LlmBlock.Text("answer to " + question))));
    }

    @Test
    void trimmingNeverSeparatesAToolCallFromItsResult() {
        ChatConversation conversation = new ChatConversation();
        appendExchange(conversation, "q1");
        appendExchange(conversation, "q2");
        appendExchange(conversation, "q3");

        conversation.trimTo(6);

        // An assistant ToolUse whose ToolResult was trimmed away — or a transcript opening with
        // orphaned tool results — is a hard 400 from the Anthropic API, so the trim must land on
        // an exchange boundary rather than a message count.
        assertThat(conversation.messages().getFirst().isPlainUserTurn()).isTrue();
        assertToolCallsAreAnswered(conversation);
        assertThat(conversation.messages()).hasSizeLessThanOrEqualTo(8);
    }

    @Test
    void trimmingIsANoOpWhenTheTranscriptFits() {
        ChatConversation conversation = new ChatConversation();
        appendExchange(conversation, "q1");

        conversation.trimTo(40);

        assertThat(conversation.messages()).hasSize(4);
    }

    @Test
    void survivesJavaSerialisation() throws Exception {
        // The transcript is an HTTP session attribute, and Spring Session serialises the session
        // into Valkey — anything unserialisable here fails at runtime, not at compile time.
        ChatConversation conversation = new ChatConversation();
        appendExchange(conversation, "q1");

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
            out.writeObject(conversation);
        }
        ChatConversation restored;
        try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            restored = (ChatConversation) in.readObject();
        }

        assertThat(restored.messages()).hasSize(4);
        assertThat(restored.messages().getFirst().text()).isEqualTo("q1");
    }

    private static void assertToolCallsAreAnswered(ChatConversation conversation) {
        List<String> calls = conversation.messages().stream()
                .flatMap(m -> m.blocks().stream())
                .filter(LlmBlock.ToolUse.class::isInstance)
                .map(b -> ((LlmBlock.ToolUse) b).id())
                .toList();
        List<String> results = conversation.messages().stream()
                .flatMap(m -> m.blocks().stream())
                .filter(LlmBlock.ToolResult.class::isInstance)
                .map(b -> ((LlmBlock.ToolResult) b).toolUseId())
                .toList();
        assertThat(results).containsExactlyElementsOf(calls);
    }
}
