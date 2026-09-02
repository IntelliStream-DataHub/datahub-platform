// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.dhconsole.chat.llm;

import ai.intellistream.dhconsole.chat.config.AgentSettings;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * A scripted {@link LlmClient} so the whole agent loop can be tested with no network and no API
 * key. Every behaviour that matters — result batching, the iteration cap, tool filtering — is
 * exercised through this.
 */
public final class StubLlmClient implements LlmClient {

    /** What the loop asked for on one turn. */
    public record Sent(AgentSettings settings, String systemPrompt, List<LlmToolDef> tools,
                       List<LlmMessage> messages, ChatEffort effort) {
    }

    private final Deque<LlmTurn> script = new ArrayDeque<>();
    private final List<Sent> sent = new ArrayList<>();
    private LlmTurn whenScriptRunsOut = new LlmTurn(List.of(new LlmBlock.Text("done")), false);

    public StubLlmClient thenText(String text) {
        script.add(new LlmTurn(List.of(new LlmBlock.Text(text)), false));
        return this;
    }

    public StubLlmClient thenToolCalls(LlmBlock.ToolUse... calls) {
        script.add(new LlmTurn(List.of(calls), true));
        return this;
    }

    /** Makes the model ask for a tool forever — used to drive the iteration cap. */
    public StubLlmClient alwaysAsksFor(LlmBlock.ToolUse call) {
        whenScriptRunsOut = new LlmTurn(List.of(call), true);
        return this;
    }

    @Override
    public LlmTurn send(AgentSettings settings, String systemPrompt, List<LlmToolDef> tools,
                        List<LlmMessage> messages, ChatEffort effort) {
        // Copy: the loop hands over an unmodifiable *view* of the live transcript, which keeps
        // changing after this returns. Recording the view would make every assertion see the
        // final state instead of the state at this turn.
        sent.add(new Sent(settings, systemPrompt, List.copyOf(tools), List.copyOf(messages), effort));
        LlmTurn next = script.poll();
        return next != null ? next : whenScriptRunsOut;
    }

    @Override
    public String providerId(AgentSettings settings) {
        return "stub";
    }

    public List<Sent> sent() {
        return sent;
    }

    public Sent firstSent() {
        return sent.getFirst();
    }
}
