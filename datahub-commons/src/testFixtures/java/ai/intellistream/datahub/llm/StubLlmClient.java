// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.llm;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * A scripted {@link LlmClient} so a whole tool-use loop can be tested with no network and no API
 * key. Every behaviour that matters — result batching, the iteration cap, tool filtering — is
 * exercised through this.
 */
public final class StubLlmClient implements LlmClient {

    /** What the loop asked for on one turn. */
    public record Sent(LlmSettings settings, String systemPrompt, List<LlmToolDef> tools,
                       List<LlmMessage> messages, LlmEffort effort) {
    }

    private final Deque<LlmTurn> script = new ArrayDeque<>();
    private final List<Sent> sent = new ArrayList<>();
    private LlmTurn whenScriptRunsOut = answer("done");

    private static LlmTurn answer(String text) {
        return new LlmTurn(List.of(new LlmBlock.Text(text)), false, LlmTurn.StopReason.END_TURN,
                LlmTurn.Usage.NONE);
    }

    public StubLlmClient thenText(String text) {
        script.add(answer(text));
        return this;
    }

    public StubLlmClient thenToolCalls(LlmBlock.ToolUse... calls) {
        script.add(new LlmTurn(List.of(calls), true, LlmTurn.StopReason.TOOL_USE, LlmTurn.Usage.NONE));
        return this;
    }

    /** Any turn at all, for stop reasons and usage the two shortcuts above do not script. */
    public StubLlmClient then(LlmTurn turn) {
        script.add(turn);
        return this;
    }

    /** Makes the model ask for a tool forever — used to drive the iteration cap. */
    public StubLlmClient alwaysAsksFor(LlmBlock.ToolUse call) {
        whenScriptRunsOut = new LlmTurn(List.of(call), true, LlmTurn.StopReason.TOOL_USE, LlmTurn.Usage.NONE);
        return this;
    }

    @Override
    public LlmTurn send(LlmSettings settings, String systemPrompt, List<LlmToolDef> tools,
                        List<LlmMessage> messages, LlmEffort effort) {
        // Copy: the loop hands over an unmodifiable *view* of the live transcript, which keeps
        // changing after this returns. Recording the view would make every assertion see the
        // final state instead of the state at this turn.
        sent.add(new Sent(settings, systemPrompt, List.copyOf(tools), List.copyOf(messages), effort));
        LlmTurn next = script.poll();
        return next != null ? next : whenScriptRunsOut;
    }

    @Override
    public String providerId(LlmSettings settings) {
        return "stub";
    }

    public List<Sent> sent() {
        return sent;
    }

    public Sent firstSent() {
        return sent.getFirst();
    }
}
