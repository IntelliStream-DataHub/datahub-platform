// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.dhconsole.chat.llm;

import ai.intellistream.dhconsole.chat.config.ChatSettings;
import ai.intellistream.datahub.tenant.LlmProvider;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The one test that spends money. Excluded from {@code test} by the {@code integration} tag; run it
 * deliberately when the adapter or the model id changes:
 *
 * <pre>
 * ./gradlew :datahub-console:integrationTest --tests '*AnthropicLlmClientIT'
 * </pre>
 *
 * <p>with a key in either {@code ANTHROPIC_API_KEY} or {@code application-test.properties}. Without
 * one every test is skipped with a reason rather than quietly passing.
 *
 * <p>Two short turns, a few cents. What it proves that the local-server test cannot: that the model
 * id is real and that the API accepts our tool schema and message shapes.
 */
@Tag("integration")
class AnthropicLlmClientIT {

    /**
     * The key, from {@code ANTHROPIC_API_KEY} or from {@code datahub.chat.api-key} in
     * {@code application-test.properties} — which is gitignored, so a real key can sit there without
     * risk of being committed. The environment wins, so CI does not need a file.
     */
    private static String apiKey() {
        String fromEnvironment = System.getenv("ANTHROPIC_API_KEY");
        if (fromEnvironment != null && !fromEnvironment.isBlank()) {
            return fromEnvironment;
        }
        try (InputStream in = AnthropicLlmClientIT.class.getResourceAsStream("/application-test.properties")) {
            if (in == null) {
                return null;
            }
            Properties properties = new Properties();
            properties.load(in);
            String configured = properties.getProperty("datahub.chat.api-key");
            return configured == null || configured.isBlank() ? null : configured;
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Skipped, not silently absent. {@code @EnabledIfEnvironmentVariable} made a keyless run look
     * like a run — the class never started, so nothing said why. An assumption aborts each test with
     * a reason that names both ways to supply a key, which is what someone who just asked for this
     * test needs to read.
     */
    @BeforeEach
    void needsAKey() {
        assumeTrue(apiKey() != null,
                "No Anthropic key. Set ANTHROPIC_API_KEY, or datahub.chat.api-key in "
                        + "datahub-console/src/main/resources/application-test.properties (gitignored).");
    }

    private static final LlmToolDef ECHO_TOOL = new LlmToolDef(
            "unit_list",
            "List the units of measure configured for this tenant.",
            """
            {"type":"object","properties":{"limit":{"type":"integer","description":"Max rows"}}}""");

    private AnthropicLlmClient client() {
        return new AnthropicLlmClient(
                AnthropicOkHttpClient.builder().apiKey(apiKey()).build(),
                JsonMapper.builder().build());
    }

    /** A 1024-token roof, so a run of this test costs pennies rather than pounds. */
    private static ChatSettings settings() {
        return new ChatSettings(LlmProvider.ANTHROPIC, null, "claude-opus-5", null, null,
                java.time.Duration.ofMinutes(4), 1024);
    }

    /**
     * What the stub-server test cannot prove: that the API accepts adaptive thinking together with
     * each effort level, on whatever model is configured. A level the model does not know is a 400 —
     * {@code xhigh} does not exist below Opus 4.7 — and so is pairing disabled thinking with
     * {@code xhigh}/{@code max} on Opus 5. Both are invisible locally.
     *
     * <p>Only the three cheap levels run. {@code xhigh} and {@code max} carry an output-token floor
     * of 16k and 32k (see {@link ChatEffort}), which overrides the 1024 set above, so covering them
     * here would mean paying for up to 32k tokens per call.
     */
    @Test
    void theApiAcceptsAdaptiveThinkingAtEveryCheapEffortLevel() {
        for (ChatEffort effort : List.of(ChatEffort.LOW, ChatEffort.MEDIUM, ChatEffort.HIGH)) {
            LlmTurn turn = client().send(settings(),
                    "Answer in one word.",
                    List.of(),
                    List.of(LlmMessage.user("Say OK and nothing else.")),
                    effort);

            assertThat(turn.text()).as("effort %s", effort).isNotBlank();
            assertThat(turn.wantsTools()).as("effort %s", effort).isFalse();
        }
    }

    @Test
    void answersAPlainQuestion() {
        LlmTurn turn = client().send(settings(),
                    "Answer in exactly one short sentence.",
                List.of(),
                List.of(LlmMessage.user("Say OK and nothing else.")), ChatEffort.HIGH);

        assertThat(turn.wantsTools()).isFalse();
        assertThat(turn.text()).isNotBlank();
    }

    @Test
    void acceptsOurToolSchemaAndAsksToUseIt() {
        LlmTurn turn = client().send(settings(),
                    "You are a data assistant. Use the tools to answer; never guess.",
                List.of(ECHO_TOOL),
                List.of(LlmMessage.user("What units are configured? Use the tool.")), ChatEffort.HIGH);

        assertThat(turn.wantsTools()).isTrue();
        assertThat(turn.toolUses()).extracting(LlmBlock.ToolUse::name).contains("unit_list");
    }
}
