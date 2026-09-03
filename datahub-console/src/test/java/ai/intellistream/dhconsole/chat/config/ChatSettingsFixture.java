// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.dhconsole.chat.config;

import ai.intellistream.datahub.tenant.LlmProvider;
import ai.intellistream.dhconsole.chat.llm.ChatEffort;

import java.time.Duration;

/** Ready-made {@link ChatSettings} for tests. */
public final class ChatSettingsFixture {

    private static final Duration TURN_TIMEOUT = Duration.ofMinutes(4);
    private static final int MAX_ITERATIONS = 6;

    private ChatSettingsFixture() {
    }

    public static ChatSettings anthropic() {
        return anthropic(null);
    }

    public static ChatSettings anthropic(Integer maxOutputTokens) {
        return new ChatSettings(LlmProvider.ANTHROPIC, "test-key", "claude-opus-5", null, null,
                TURN_TIMEOUT, maxOutputTokens, ChatEffort.DEFAULT, MAX_ITERATIONS, null);
    }

    public static ChatSettings openAiCompatible(String baseUrl, String reasoningEffort) {
        return new ChatSettings(LlmProvider.OPENAI_COMPATIBLE, null, "qwen3.5:latest", baseUrl,
                reasoningEffort, TURN_TIMEOUT, null, ChatEffort.DEFAULT, MAX_ITERATIONS, null);
    }

    /** For the loop's own limits, which are the tenant's now rather than the deployment's. */
    public static ChatSettings anthropicWith(int maxIterations, String instructions) {
        return new ChatSettings(LlmProvider.ANTHROPIC, "test-key", "claude-opus-5", null, null,
                TURN_TIMEOUT, null, ChatEffort.DEFAULT, maxIterations, instructions);
    }
}
