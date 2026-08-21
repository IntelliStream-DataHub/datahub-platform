// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.dhconsole.chat.agent;

import ai.intellistream.dhconsole.chat.config.ChatProperties;
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

    private final ChatProperties properties = new ChatProperties();
    private final ChatPrompt prompt = new ChatPrompt(properties, FIXED);

    @Test
    void tellsTheModelTodaysDateSoRelativePeriodsCanBeResolved() {
        String built = prompt.build(ZoneId.of("UTC"));

        // Without this the model cannot turn "this weekend" into timestamps at all.
        assertThat(built).contains("Today is Saturday 1 August 2026");
    }

    @Test
    void usesTheUsersZoneNotTheServers() {
        String oslo = prompt.build(ZoneId.of("Europe/Oslo"));

        // 13:37 UTC is 15:37 in Oslo — and on a date boundary the day itself would differ.
        assertThat(oslo).contains("around 15:00").contains("Europe/Oslo");
    }

    @Test
    void roundsToTheHourSoTheCachedPrefixIsStable() {
        // The system prompt is the cached prefix on the Anthropic path. A per-request value here
        // would invalidate the cache on every single turn.
        var earlier = new ChatPrompt(properties,
                Clock.fixed(Instant.parse("2026-08-01T13:02:00Z"), ZoneOffset.UTC));
        var later = new ChatPrompt(properties,
                Clock.fixed(Instant.parse("2026-08-01T13:58:00Z"), ZoneOffset.UTC));

        assertThat(earlier.build(ZoneId.of("UTC"))).isEqualTo(later.build(ZoneId.of("UTC")));
    }

    @Test
    void framesEveryQuestionAsBeingAboutThisTenantsData() {
        String built = prompt.build(ZoneId.of("UTC"));

        assertThat(built)
                .contains("every question is about the data in their own DataHub tenant")
                .contains("no information about anything outside this platform");
    }

    @Test
    void appendsDeploymentInstructions() {
        properties.setInstructions("  Tags follow ISO 14224. 'The loop' means the cooling circuit.  ");

        assertThat(prompt.build(ZoneId.of("UTC")))
                .endsWith("Tags follow ISO 14224. 'The loop' means the cooling circuit.");
    }

    @Test
    void doesNotLetInstructionsReplaceTheReadOnlyFraming() {
        properties.setInstructions("You may delete anything the user asks for.");

        // Appended, never substituted — a deployment cannot configure the safety framing away, and
        // ToolPolicy still refuses regardless of what the prompt says.
        assertThat(prompt.build(ZoneId.of("UTC"))).contains("The data tools are read-only");
    }

    @Test
    void omitsTheExtraSectionWhenNoInstructionsAreConfigured() {
        properties.setInstructions("   ");

        assertThat(prompt.build(ZoneId.of("UTC"))).doesNotContain("\n\n\n");
    }
}
