// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.llm;

import ai.intellistream.datahub.tenant.LlmProvider;

import java.time.Duration;

/** Ready-made {@link LlmSettings} for tests. */
public final class LlmSettingsFixture {

    private static final Duration TURN_TIMEOUT = Duration.ofMinutes(4);
    private static final int MAX_ITERATIONS = 6;

    private LlmSettingsFixture() {
    }

    public static LlmSettings anthropic() {
        return anthropic(null);
    }

    public static LlmSettings anthropic(Integer maxOutputTokens) {
        return new LlmSettings(LlmProvider.ANTHROPIC, "test-key", "claude-opus-5", null, null,
                TURN_TIMEOUT, maxOutputTokens, LlmEffort.DEFAULT, MAX_ITERATIONS, null);
    }

    public static LlmSettings openAiCompatible(String baseUrl, String reasoningEffort) {
        return new LlmSettings(LlmProvider.OPENAI_COMPATIBLE, null, "qwen3.5:latest", baseUrl,
                reasoningEffort, TURN_TIMEOUT, null, LlmEffort.DEFAULT, MAX_ITERATIONS, null);
    }

    /** For the loop's own limits, which are the tenant's rather than the deployment's. */
    public static LlmSettings anthropicWith(int maxIterations, String instructions) {
        return new LlmSettings(LlmProvider.ANTHROPIC, "test-key", "claude-opus-5", null, null,
                TURN_TIMEOUT, null, LlmEffort.DEFAULT, maxIterations, instructions);
    }
}
