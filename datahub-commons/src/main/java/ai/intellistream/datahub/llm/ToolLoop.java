// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.llm;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * The tool-use loop: ask the model, run whatever tools it asks for, feed the results back, repeat
 * until it answers or a bound is hit.
 *
 * <p>Two things are deliberately not here. <strong>Policy</strong>: both provider adapters take the
 * tool name verbatim from the model's output, so the loop never trusts what was offered — every
 * call goes through the caller's {@link ToolExecutor}, and that executor refuses before forwarding
 * (the console's read-only allowlist, an agent's per-agent list). <strong>Wiring</strong>: who the
 * tools run as, where the transcript lives and what to log are the caller's, through the
 * {@link Transcript} and {@link Listener} it passes.
 */
public final class ToolLoop {

    /** Why the loop returned. */
    public enum Ended {
        /** The model gave a finished answer. */
        ANSWERED,
        /** It was still asking for tools at {@link LlmSettings#maxIterations()}. */
        ITERATION_CAP,
        /** Its last turn was cut off at the output ceiling; {@code lastText} is incomplete. */
        MAX_TOKENS,
        /** The caller's {@code stop} said so; the transcript is left well-formed. */
        STOPPED
    }

    /**
     * @param lastText   the answer, or the last prose the model produced when there is none
     * @param iterations model calls made
     * @param usage      the sum over those calls
     */
    public record Outcome(Ended ended, String lastText, int iterations, LlmTurn.Usage usage) {
    }

    /** Runs one tool call, or refuses it: the result is what the model sees either way. */
    @FunctionalInterface
    public interface ToolExecutor {
        LlmBlock.ToolResult execute(LlmBlock.ToolUse call);
    }

    /** The messages so far, appended to as the loop goes. */
    public interface Transcript {
        List<LlmMessage> messages();

        void append(LlmMessage message);
    }

    /** Told after each model call and each tool call, with how long it took. */
    public interface Listener {
        default void modelCalled(int iteration, LlmTurn turn, long millis) {
        }

        default void toolCalled(LlmBlock.ToolUse call, LlmBlock.ToolResult result, long millis) {
        }
    }

    private ToolLoop() {
    }

    /**
     * @param stop checked before every model call and every tool call; once true the loop ends
     *             with {@link Ended#STOPPED}, answering any tool call it did not run with an error
     *             result so the transcript stays a valid request
     */
    public static Outcome run(LlmClient llm, LlmSettings settings, String systemPrompt,
                              List<LlmToolDef> tools, Transcript transcript, LlmEffort effort,
                              ToolExecutor executor, Listener listener, BooleanSupplier stop) {
        LlmTurn.Usage usage = LlmTurn.Usage.NONE;
        for (int iteration = 1; iteration <= settings.maxIterations(); iteration++) {
            if (stop.getAsBoolean()) {
                return new Outcome(Ended.STOPPED, lastAssistantText(transcript), iteration - 1, usage);
            }
            long startedAt = System.nanoTime();
            LlmTurn turn = llm.send(settings, systemPrompt, tools, transcript.messages(), effort);
            usage = usage.plus(turn.usage());
            listener.modelCalled(iteration, turn, millisSince(startedAt));
            transcript.append(LlmMessage.assistant(turn.blocks()));

            List<LlmBlock.ToolUse> calls = turn.toolUses();
            if (!turn.wantsTools() || calls.isEmpty()) {
                // A tool_use stop with no tool blocks is answered like prose: a result message with
                // nothing in it would be a 400 on the next call.
                Ended ended = turn.stopReason() == LlmTurn.StopReason.MAX_TOKENS
                        ? Ended.MAX_TOKENS : Ended.ANSWERED;
                return new Outcome(ended, turn.text(), iteration, usage);
            }

            List<LlmBlock> results = new ArrayList<>();
            boolean stopped = false;
            for (LlmBlock.ToolUse call : calls) {
                if (stopped || stop.getAsBoolean()) {
                    stopped = true;
                    results.add(new LlmBlock.ToolResult(call.id(), "Not run: the turn was stopped.", true));
                    continue;
                }
                long toolStartedAt = System.nanoTime();
                LlmBlock.ToolResult result = executor.execute(call);
                listener.toolCalled(call, result, millisSince(toolStartedAt));
                results.add(result);
            }
            // One message carrying every result from this assistant turn — splitting them would
            // teach the model to stop making parallel calls, and Anthropic rejects the shape.
            transcript.append(LlmMessage.toolResults(results));
            if (stopped) {
                return new Outcome(Ended.STOPPED, lastAssistantText(transcript), iteration, usage);
            }
        }
        return new Outcome(Ended.ITERATION_CAP, lastAssistantText(transcript), settings.maxIterations(), usage);
    }

    /**
     * {@code text} cut to {@code max} characters, telling the model it was cut, so it narrows the
     * query rather than reasoning over a half-object it thinks is complete.
     */
    public static String truncate(String text, int max) {
        if (text.length() <= max) {
            return text;
        }
        return text.substring(0, max) + "\n…[truncated; narrow the query to see more]";
    }

    private static String lastAssistantText(Transcript transcript) {
        List<LlmMessage> messages = transcript.messages();
        for (int i = messages.size() - 1; i >= 0; i--) {
            LlmMessage message = messages.get(i);
            if (message.role() == LlmMessage.Role.ASSISTANT && !message.text().isBlank()) {
                return message.text();
            }
        }
        return "";
    }

    private static long millisSince(long startedAtNanos) {
        return (System.nanoTime() - startedAtNanos) / 1_000_000;
    }
}
