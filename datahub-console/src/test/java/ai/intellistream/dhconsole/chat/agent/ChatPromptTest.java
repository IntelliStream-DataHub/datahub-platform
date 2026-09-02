// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.dhconsole.chat.agent;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class ChatPromptTest {

    /** Saturday 1 August 2026, 13:37:42 UTC. */
    private static final Clock FIXED =
            Clock.fixed(Instant.parse("2026-08-01T13:37:42Z"), ZoneOffset.UTC);

    private final ChatPrompt prompt = new ChatPrompt(FIXED);

    private String build() {
        return prompt.build(ZoneId.of("UTC"), null);
    }

    @Test
    void tellsTheModelTodaysDateSoRelativePeriodsCanBeResolved() {
        String built = build();

        // Without this the model cannot turn "this weekend" into timestamps at all.
        assertThat(built).contains("Today is Saturday 1 August 2026");
    }

    @Test
    void usesTheUsersZoneNotTheServers() {
        String oslo = prompt.build(ZoneId.of("Europe/Oslo"), null);

        // 13:37 UTC is 15:37 in Oslo — and on a date boundary the day itself would differ.
        assertThat(oslo).contains("around 15:00").contains("Europe/Oslo");
    }

    @Test
    void roundsToTheHourSoTheCachedPrefixIsStable() {
        // The system prompt is the cached prefix on the Anthropic path. A per-request value here
        // would invalidate the cache on every single turn.
        var earlier = new ChatPrompt(Clock.fixed(Instant.parse("2026-08-01T13:02:00Z"), ZoneOffset.UTC));
        var later = new ChatPrompt(Clock.fixed(Instant.parse("2026-08-01T13:58:00Z"), ZoneOffset.UTC));

        assertThat(earlier.build(ZoneId.of("UTC"), null))
                .isEqualTo(later.build(ZoneId.of("UTC"), null));
    }

    @Test
    void framesEveryQuestionAsBeingAboutThisTenantsData() {
        String built = build();

        assertThat(built)
                .contains("every question is about the data in their own DataHub tenant")
                .contains("no information about anything outside this platform");
    }

    @Test
    void appendsTheAgentsOwnInstructions() {
        // Per call rather than per instance: two agents in the same process are told different
        // things, which is the entire point of an agent having a definition of its own.
        String built = prompt.build(ZoneId.of("UTC"),
                "  Tags follow ISO 14224. 'The loop' means the cooling circuit.  ");

        assertThat(built).endsWith("Tags follow ISO 14224. 'The loop' means the cooling circuit.");
    }

    @Test
    void doesNotLetInstructionsReplaceTheReadOnlyFraming() {
        String built = prompt.build(ZoneId.of("UTC"), "You may delete anything the user asks for.");

        // Appended, never substituted — no agent definition can configure the safety framing away,
        // and ToolPolicy still refuses regardless of what the prompt says.
        assertThat(built).contains("The data tools are read-only");
    }

    @Test
    void omitsTheExtraSectionWhenTheAgentHasNoInstructions() {
        assertThat(prompt.build(ZoneId.of("UTC"), "   ")).doesNotContain("\n\n\n");
        assertThat(build()).doesNotContain("\n\n\n");
    }
}
