// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.dhconsole.chat.llm;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The level arrives from a browser, so what matters here is that nothing a stale or hand-edited
 * panel can send turns into a failed turn — and that the deep levels bring the output room they
 * need with them.
 */
class ChatEffortTest {

    @Test
    void parsesTheSpellingsABrowserMightSend() {
        assertThat(ChatEffort.parse("xhigh", ChatEffort.HIGH)).isEqualTo(ChatEffort.XHIGH);
        assertThat(ChatEffort.parse("XHIGH", ChatEffort.HIGH)).isEqualTo(ChatEffort.XHIGH);
        assertThat(ChatEffort.parse("x-high", ChatEffort.HIGH)).isEqualTo(ChatEffort.XHIGH);
        assertThat(ChatEffort.parse("  max  ", ChatEffort.HIGH)).isEqualTo(ChatEffort.MAX);
    }

    @Test
    void fallsBackRatherThanFailingTheTurn() {
        assertThat(ChatEffort.parse(null, ChatEffort.MEDIUM)).isEqualTo(ChatEffort.MEDIUM);
        assertThat(ChatEffort.parse("", ChatEffort.MEDIUM)).isEqualTo(ChatEffort.MEDIUM);
        assertThat(ChatEffort.parse("   ", ChatEffort.MEDIUM)).isEqualTo(ChatEffort.MEDIUM);
        // A level that no longer exists (renamed, or someone's curl) is the deployment's default,
        // not a 400: the enum bounds what can reach the provider either way.
        assertThat(ChatEffort.parse("ludicrous", ChatEffort.MEDIUM)).isEqualTo(ChatEffort.MEDIUM);
    }

    @Test
    void theDeepestLevelsWantMoreRoomThanTheRest() {
        assertThat(ChatEffort.LOW.defaultOutputTokens()).isEqualTo(4_096);
        assertThat(ChatEffort.HIGH.defaultOutputTokens()).isEqualTo(4_096);
        assertThat(ChatEffort.XHIGH.defaultOutputTokens()).isEqualTo(16_000);
        assertThat(ChatEffort.MAX.defaultOutputTokens()).isEqualTo(32_000);
    }

    @Test
    void narrowsOntoTheThreeValuesTheOpenAiWireDefines() {
        assertThat(ChatEffort.LOW.openAiReasoningEffort()).isEqualTo("low");
        assertThat(ChatEffort.MEDIUM.openAiReasoningEffort()).isEqualTo("medium");
        assertThat(ChatEffort.HIGH.openAiReasoningEffort()).isEqualTo("high");
        assertThat(ChatEffort.XHIGH.openAiReasoningEffort()).isEqualTo("high");
        assertThat(ChatEffort.MAX.openAiReasoningEffort()).isEqualTo("high");
    }

    @Test
    void wireNamesMatchWhatThePickerSends() {
        assertThat(ChatEffort.XHIGH.wireName()).isEqualTo("xhigh");
        for (ChatEffort effort : ChatEffort.values()) {
            assertThat(ChatEffort.parse(effort.wireName(), ChatEffort.LOW)).isEqualTo(effort);
        }
    }
}
