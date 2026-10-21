// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.llm;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The level arrives from a browser or a hand-edited secret, so what matters here is that nothing
 * a stale or hand-edited value can send turns into a failed turn — and that the deep levels bring
 * the output room they need with them.
 */
class LlmEffortTest {

    @Test
    void parsesTheSpellingsABrowserMightSend() {
        assertThat(LlmEffort.parse("xhigh", LlmEffort.HIGH)).isEqualTo(LlmEffort.XHIGH);
        assertThat(LlmEffort.parse("XHIGH", LlmEffort.HIGH)).isEqualTo(LlmEffort.XHIGH);
        assertThat(LlmEffort.parse("x-high", LlmEffort.HIGH)).isEqualTo(LlmEffort.XHIGH);
        assertThat(LlmEffort.parse("  max  ", LlmEffort.HIGH)).isEqualTo(LlmEffort.MAX);
    }

    @Test
    void fallsBackRatherThanFailingTheTurn() {
        assertThat(LlmEffort.parse(null, LlmEffort.MEDIUM)).isEqualTo(LlmEffort.MEDIUM);
        assertThat(LlmEffort.parse("", LlmEffort.MEDIUM)).isEqualTo(LlmEffort.MEDIUM);
        assertThat(LlmEffort.parse("   ", LlmEffort.MEDIUM)).isEqualTo(LlmEffort.MEDIUM);
        // A level that no longer exists (renamed, or someone's curl) is the deployment's default,
        // not a 400: the enum bounds what can reach the provider either way.
        assertThat(LlmEffort.parse("ludicrous", LlmEffort.MEDIUM)).isEqualTo(LlmEffort.MEDIUM);
    }

    @Test
    void theDeepestLevelsWantMoreRoomThanTheRest() {
        assertThat(LlmEffort.LOW.defaultOutputTokens()).isEqualTo(4_096);
        assertThat(LlmEffort.HIGH.defaultOutputTokens()).isEqualTo(4_096);
        assertThat(LlmEffort.XHIGH.defaultOutputTokens()).isEqualTo(16_000);
        assertThat(LlmEffort.MAX.defaultOutputTokens()).isEqualTo(32_000);
    }

    @Test
    void narrowsOntoTheThreeValuesTheOpenAiWireDefines() {
        assertThat(LlmEffort.LOW.openAiReasoningEffort()).isEqualTo("low");
        assertThat(LlmEffort.MEDIUM.openAiReasoningEffort()).isEqualTo("medium");
        assertThat(LlmEffort.HIGH.openAiReasoningEffort()).isEqualTo("high");
        assertThat(LlmEffort.XHIGH.openAiReasoningEffort()).isEqualTo("high");
        assertThat(LlmEffort.MAX.openAiReasoningEffort()).isEqualTo("high");
    }

    @Test
    void wireNamesMatchWhatThePickerSends() {
        assertThat(LlmEffort.XHIGH.wireName()).isEqualTo("xhigh");
        for (LlmEffort effort : LlmEffort.values()) {
            assertThat(LlmEffort.parse(effort.wireName(), LlmEffort.LOW)).isEqualTo(effort);
        }
    }
}
