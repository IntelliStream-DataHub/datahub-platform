// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.llm;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static ai.intellistream.datahub.llm.LlmSettingsFixture.anthropic;
import static ai.intellistream.datahub.llm.LlmSettingsFixture.anthropicWith;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The loop with a scripted model and a recording executor. What it pins: every tool call goes
 * through the executor and nowhere else, results from one turn travel as one message, and each
 * way the loop can end leaves a transcript the next call would accept.
 */
class ToolLoopTest {

    private static final List<LlmToolDef> TOOLS = List.of(new LlmToolDef("dataset_list", "Browse datasets.", "{}"));

    /** An ArrayList-backed transcript, which is all the loop needs. */
    private static ToolLoop.Transcript transcript(LlmMessage... opening) {
        List<LlmMessage> messages = new ArrayList<>(List.of(opening));
        return new ToolLoop.Transcript() {
            @Override
            public List<LlmMessage> messages() {
                return List.copyOf(messages);
            }

            @Override
            public void append(LlmMessage message) {
                messages.add(message);
            }
        };
    }

    private static final ToolLoop.Listener QUIET = new ToolLoop.Listener() {
    };

    private final List<LlmBlock.ToolUse> executed = new ArrayList<>();

    private final ToolLoop.ToolExecutor recording = call -> {
        executed.add(call);
        return new LlmBlock.ToolResult(call.id(), "{\"items\":[]}", false);
    };

    @Test
    void answersWhenTheModelDoes() {
        StubLlmClient llm = new StubLlmClient().thenText("Three datasets.");

        ToolLoop.Outcome outcome = ToolLoop.run(llm, anthropic(), "system", TOOLS,
                transcript(LlmMessage.user("how many?")), LlmEffort.HIGH, recording, QUIET, () -> false);

        assertThat(outcome.ended()).isEqualTo(ToolLoop.Ended.ANSWERED);
        assertThat(outcome.lastText()).isEqualTo("Three datasets.");
        assertThat(outcome.iterations()).isEqualTo(1);
        assertThat(executed).isEmpty();
    }

    @Test
    void everyToolCallGoesThroughTheExecutorAndResultsTravelAsOneMessage() {
        StubLlmClient llm = new StubLlmClient()
                .thenToolCalls(
                        new LlmBlock.ToolUse("t1", "dataset_list", Map.of()),
                        new LlmBlock.ToolUse("t2", "timeseries_search", Map.of("query", "pump")))
                .thenText("Here is what I found.");
        ToolLoop.Transcript transcript = transcript(LlmMessage.user("what do I have?"));

        ToolLoop.Outcome outcome = ToolLoop.run(llm, anthropic(), "system", TOOLS, transcript,
                LlmEffort.HIGH, recording, QUIET, () -> false);

        assertThat(outcome.ended()).isEqualTo(ToolLoop.Ended.ANSWERED);
        assertThat(executed).extracting(LlmBlock.ToolUse::id).containsExactly("t1", "t2");
        // user, assistant(tool_use x2), user(results x2), assistant(answer)
        assertThat(transcript.messages()).hasSize(4);
        LlmMessage results = transcript.messages().get(2);
        assertThat(results.role()).isEqualTo(LlmMessage.Role.USER);
        assertThat(results.blocks()).extracting(b -> ((LlmBlock.ToolResult) b).toolUseId())
                .containsExactly("t1", "t2");
        // The second call saw the results of the first.
        assertThat(llm.sent().get(1).messages()).hasSize(3);
    }

    @Test
    void aRefusedCallNeverReachesAnythingButTheModel() {
        // The executor is the single gate: a tool the model was never offered is still a tool
        // call, and this is where it is refused. The loop itself has no opinion.
        StubLlmClient llm = new StubLlmClient()
                .thenToolCalls(new LlmBlock.ToolUse("t1", "dataset_delete", Map.of("id", 1)))
                .thenText("I cannot do that.");
        ToolLoop.Transcript transcript = transcript(LlmMessage.user("delete it"));
        ToolLoop.ToolExecutor refusing = call ->
                new LlmBlock.ToolResult(call.id(), "This tool is not available.", true);

        ToolLoop.run(llm, anthropic(), "system", TOOLS, transcript, LlmEffort.HIGH, refusing, QUIET, () -> false);

        LlmBlock.ToolResult refusal = (LlmBlock.ToolResult) transcript.messages().get(2).blocks().getFirst();
        assertThat(refusal.isError()).isTrue();
        assertThat(llm.sent().get(1).messages().get(2).blocks()).containsExactly(refusal);
    }

    @Test
    void stopsAtTheIterationCapWithTheLastProseItHas() {
        StubLlmClient llm = new StubLlmClient()
                .thenToolCalls(new LlmBlock.ToolUse("t0", "dataset_list", Map.of()))
                .then(new LlmTurn(List.of(new LlmBlock.Text("Looking further."),
                        new LlmBlock.ToolUse("t1", "dataset_list", Map.of())), true,
                        LlmTurn.StopReason.TOOL_USE, LlmTurn.Usage.NONE))
                .alwaysAsksFor(new LlmBlock.ToolUse("t", "dataset_list", Map.of()));

        ToolLoop.Outcome outcome = ToolLoop.run(llm, anthropicWith(3, null), "system", TOOLS,
                transcript(LlmMessage.user("loop")), LlmEffort.HIGH, recording, QUIET, () -> false);

        assertThat(outcome.ended()).isEqualTo(ToolLoop.Ended.ITERATION_CAP);
        assertThat(outcome.iterations()).isEqualTo(3);
        assertThat(executed).hasSize(3);
        assertThat(outcome.lastText()).isEqualTo("Looking further.");
    }

    @Test
    void anAnswerCutOffAtTheOutputCeilingIsNotReportedAsAnAnswer() {
        StubLlmClient llm = new StubLlmClient().then(new LlmTurn(
                List.of(new LlmBlock.Text("The pump is")), false, LlmTurn.StopReason.MAX_TOKENS,
                LlmTurn.Usage.NONE));

        ToolLoop.Outcome outcome = ToolLoop.run(llm, anthropic(), "system", TOOLS,
                transcript(LlmMessage.user("why?")), LlmEffort.HIGH, recording, QUIET, () -> false);

        assertThat(outcome.ended()).isEqualTo(ToolLoop.Ended.MAX_TOKENS);
        assertThat(outcome.lastText()).isEqualTo("The pump is");
    }

    @Test
    void aToolUseStopWithNoToolBlocksIsAnAnswer() {
        // An empty results message would be a 400 on the next call; there is nothing to run.
        StubLlmClient llm = new StubLlmClient().then(new LlmTurn(
                List.of(new LlmBlock.Text("Hmm.")), true, LlmTurn.StopReason.TOOL_USE, LlmTurn.Usage.NONE));
        ToolLoop.Transcript transcript = transcript(LlmMessage.user("?"));

        ToolLoop.Outcome outcome = ToolLoop.run(llm, anthropic(), "system", TOOLS, transcript,
                LlmEffort.HIGH, recording, QUIET, () -> false);

        assertThat(outcome.ended()).isEqualTo(ToolLoop.Ended.ANSWERED);
        assertThat(transcript.messages()).hasSize(2);
    }

    @Test
    void stoppingMidBatchAnswersTheRemainingCallsSoTheTranscriptStaysValid() {
        // A cancelled agent run: the first tool ran, the stop flag went up, the second must not
        // run — and the assistant's tool_use blocks must still all have a result, or the
        // transcript could never be sent again.
        StubLlmClient llm = new StubLlmClient()
                .thenToolCalls(
                        new LlmBlock.ToolUse("t1", "dataset_list", Map.of()),
                        new LlmBlock.ToolUse("t2", "dataset_list", Map.of()))
                .thenText("never reached");
        ToolLoop.Transcript transcript = transcript(LlmMessage.user("go"));
        AtomicInteger stopAfter = new AtomicInteger(1);
        ToolLoop.ToolExecutor countingDown = call -> {
            stopAfter.decrementAndGet();
            return recording.execute(call);
        };

        ToolLoop.Outcome outcome = ToolLoop.run(llm, anthropic(), "system", TOOLS, transcript,
                LlmEffort.HIGH, countingDown, QUIET, () -> stopAfter.get() <= 0);

        assertThat(outcome.ended()).isEqualTo(ToolLoop.Ended.STOPPED);
        assertThat(executed).extracting(LlmBlock.ToolUse::id).containsExactly("t1");
        assertThat(llm.sent()).hasSize(1);
        LlmMessage results = transcript.messages().get(2);
        assertThat(results.blocks()).hasSize(2);
        LlmBlock.ToolResult notRun = (LlmBlock.ToolResult) results.blocks().get(1);
        assertThat(notRun.toolUseId()).isEqualTo("t2");
        assertThat(notRun.isError()).isTrue();
    }

    @Test
    void aStopBeforeTheFirstCallMakesNoCallAtAll() {
        StubLlmClient llm = new StubLlmClient().thenText("hi");

        ToolLoop.Outcome outcome = ToolLoop.run(llm, anthropic(), "system", TOOLS,
                transcript(LlmMessage.user("go")), LlmEffort.HIGH, recording, QUIET, () -> true);

        assertThat(outcome.ended()).isEqualTo(ToolLoop.Ended.STOPPED);
        assertThat(outcome.iterations()).isZero();
        assertThat(llm.sent()).isEmpty();
    }

    @Test
    void sumsUsageOverTheTurnAndReportsEachCallToTheListener() {
        StubLlmClient llm = new StubLlmClient()
                .then(new LlmTurn(List.of(new LlmBlock.ToolUse("t1", "dataset_list", Map.of())), true,
                        LlmTurn.StopReason.TOOL_USE, new LlmTurn.Usage(100, 10, 80, 0)))
                .then(new LlmTurn(List.of(new LlmBlock.Text("Done.")), false,
                        LlmTurn.StopReason.END_TURN, new LlmTurn.Usage(200, 20, 0, 5)));
        List<String> seen = new ArrayList<>();
        ToolLoop.Listener listener = new ToolLoop.Listener() {
            @Override
            public void modelCalled(int iteration, LlmTurn turn, long millis) {
                seen.add("model " + iteration + " " + turn.stopReason());
            }

            @Override
            public void toolCalled(LlmBlock.ToolUse call, LlmBlock.ToolResult result, long millis) {
                seen.add("tool " + call.name());
            }
        };

        ToolLoop.Outcome outcome = ToolLoop.run(llm, anthropic(), "system", TOOLS,
                transcript(LlmMessage.user("go")), LlmEffort.HIGH, recording, listener, () -> false);

        assertThat(outcome.usage()).isEqualTo(new LlmTurn.Usage(300, 30, 80, 5));
        assertThat(seen).containsExactly("model 1 TOOL_USE", "tool dataset_list", "model 2 END_TURN");
    }

    @Test
    void theChosenEffortAndSettingsApplyToEveryCall() {
        StubLlmClient llm = new StubLlmClient()
                .thenToolCalls(new LlmBlock.ToolUse("t1", "dataset_list", Map.of()))
                .thenText("Here.");
        LlmSettings settings = anthropicWith(4, "House style.");

        ToolLoop.run(llm, settings, "system", TOOLS, transcript(LlmMessage.user("dig")),
                LlmEffort.MAX, recording, QUIET, () -> false);

        // A turn told to think its hardest has earned an equally hard pass over the tool results.
        assertThat(llm.sent()).extracting(StubLlmClient.Sent::effort).containsOnly(LlmEffort.MAX);
        assertThat(llm.sent()).extracting(StubLlmClient.Sent::settings).containsOnly(settings);
    }

    @Test
    void truncationTellsTheModelItWasCut() {
        assertThat(ToolLoop.truncate("short", 50)).isEqualTo("short");
        assertThat(ToolLoop.truncate("x".repeat(5_000), 50))
                .hasSizeLessThan(200)
                .startsWith("x".repeat(50))
                .endsWith("narrow the query to see more]");
    }
}
