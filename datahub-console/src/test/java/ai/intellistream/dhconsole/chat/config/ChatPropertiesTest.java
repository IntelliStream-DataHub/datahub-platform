// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.dhconsole.chat.config;

import ai.intellistream.dhconsole.chat.llm.ChatEffort;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Which of two settings wins when they disagree: a roof an operator configured, or the budget the
 * effort level a user clicked would like to have.
 */
class ChatPropertiesTest {

    @Test
    void withNoRoofConfiguredTheLevelChoosesTheBudget() {
        ChatProperties properties = new ChatProperties();

        assertThat(properties.maxOutputTokensFor(ChatEffort.HIGH)).isEqualTo(4_096);
        assertThat(properties.maxOutputTokensFor(ChatEffort.XHIGH)).isEqualTo(16_000);
        assertThat(properties.maxOutputTokensFor(ChatEffort.MAX)).isEqualTo(32_000);
    }

    @Test
    void aConfiguredRoofWinsAtEveryLevel() {
        // The point of the whole rule: someone who wrote down 800 was talking about money, and a
        // per-message UI control must not be able to spend 40x that.
        ChatProperties properties = new ChatProperties();
        properties.setMaxOutputTokens(800);

        for (ChatEffort effort : ChatEffort.values()) {
            assertThat(properties.maxOutputTokensFor(effort)).as("effort %s", effort).isEqualTo(800);
        }
    }

    @Test
    void aConfiguredRoofIsObeyedEvenWhenItIsHigherThanAnyLevelWants() {
        ChatProperties properties = new ChatProperties();
        properties.setMaxOutputTokens(64_000);

        assertThat(properties.maxOutputTokensFor(ChatEffort.LOW)).isEqualTo(64_000);
        assertThat(properties.maxOutputTokensFor(ChatEffort.MAX)).isEqualTo(64_000);
    }
}
