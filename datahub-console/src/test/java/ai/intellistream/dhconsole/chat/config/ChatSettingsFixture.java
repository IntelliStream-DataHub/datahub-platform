// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.dhconsole.chat.config;

import ai.intellistream.datahub.tenant.LlmProvider;

import java.time.Duration;

/** Ready-made {@link ChatSettings} for tests. */
public final class ChatSettingsFixture {

    private ChatSettingsFixture() {
    }

    public static ChatSettings anthropic() {
        return new ChatSettings(LlmProvider.ANTHROPIC, "test-key", "claude-opus-5", null, null,
                Duration.ofMinutes(4), null);
    }

    public static ChatSettings anthropic(Integer maxOutputTokens) {
        return new ChatSettings(LlmProvider.ANTHROPIC, "test-key", "claude-opus-5", null, null,
                Duration.ofMinutes(4), maxOutputTokens);
    }

    public static ChatSettings openAiCompatible(String baseUrl, String reasoningEffort) {
        return new ChatSettings(LlmProvider.OPENAI_COMPATIBLE, null, "qwen3.5:latest", baseUrl,
                reasoningEffort, Duration.ofMinutes(4), null);
    }
}
