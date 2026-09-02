// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.dhconsole.chat.config;

import ai.intellistream.datahub.tenant.CallerPermissions;
import ai.intellistream.datahub.tenant.LlmProvider;
import ai.intellistream.dhconsole.chat.llm.ChatEffort;

import java.time.Duration;
import java.util.List;
import java.util.Set;

/**
 * Ready-made {@link AgentSettings} for tests, so a test that cares about one field does not have
 * to state the other twelve.
 */
public final class AgentSettingsFixture {

    private AgentSettingsFixture() {
    }

    /** An Anthropic-backed agent with the given tools and otherwise ordinary settings. */
    public static AgentSettings anthropicAgent(String... tools) {
        return new AgentSettings("test-agent", LlmProvider.ANTHROPIC, "test-key", "claude-opus-5",
                null, null, Duration.ofMinutes(4), null, List.of(tools), ChatEffort.HIGH,
                null, 6, 40, 24_000);
    }

    /** An agent pointed at a self-hosted OpenAI-compatible server. */
    public static AgentSettings openAiCompatibleAgent(String baseUrl, String... tools) {
        return new AgentSettings("test-agent", LlmProvider.OPENAI_COMPATIBLE, null,
                "qwen3.5:latest", baseUrl, null, Duration.ofMinutes(4), null, List.of(tools),
                ChatEffort.HIGH, null, 6, 40, 24_000);
    }

    /** A caller who may read everything — the common case, and the one that narrows nothing. */
    public static CallerPermissions readsEverything() {
        return new CallerPermissions(true, false, false, Set.of(), Set.of(), false, false);
    }

    /** A caller with grants on specific datasets. */
    public static CallerPermissions reads(Long... datasetIds) {
        return new CallerPermissions(false, false, false, Set.of(datasetIds), Set.of(), false, false);
    }

    /** A caller with no grant anywhere: signed in, but with access to nothing. */
    public static CallerPermissions readsNothing() {
        return new CallerPermissions(false, false, false, Set.of(), Set.of(), false, false);
    }
}
