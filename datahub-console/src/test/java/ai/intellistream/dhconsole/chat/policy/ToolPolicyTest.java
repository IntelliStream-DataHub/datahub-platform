// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.dhconsole.chat.policy;

import ai.intellistream.dhconsole.chat.config.AgentSettings;
import ai.intellistream.dhconsole.chat.llm.LlmToolDef;
import org.junit.jupiter.api.Test;

import java.util.List;

import static ai.intellistream.dhconsole.chat.config.AgentSettingsFixture.anthropicAgent;
import static ai.intellistream.dhconsole.chat.config.AgentSettingsFixture.reads;
import static ai.intellistream.dhconsole.chat.config.AgentSettingsFixture.readsEverything;
import static ai.intellistream.dhconsole.chat.config.AgentSettingsFixture.readsNothing;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The three-way rule: advertised ∩ the agent's allowlist ∩ what the identity may do.
 *
 * <p>Every test here is a way of getting no tool rather than a tool, because that is the property
 * worth defending. The old version of this test pinned a hardcoded list of twenty names; that list
 * now lives in datahub-api's {@code ToolCatalog}, next to the tools it describes, and is pinned by
 * {@code ToolCatalogTest} against the real {@code @Tool} methods.
 */
class ToolPolicyTest {

    private final ToolPolicy policy = new ToolPolicy();

    private static final List<LlmToolDef> ADVERTISED = List.of(
            new LlmToolDef("dataset_list", "Browse datasets.", "{}"),
            new LlmToolDef("event_search", "Search events.", "{}"),
            new LlmToolDef("timeseries_get", "Get a series.", "{}"));

    private List<String> allowedNames(AgentSettings settings, ai.intellistream.datahub.tenant.CallerPermissions permissions) {
        return policy.selectAllowed(ADVERTISED, settings, permissions).stream()
                .map(LlmToolDef::name).toList();
    }

    @Test
    void offersTheIntersectionOfWhatIsAdvertisedAndWhatTheAgentMayUse() {
        AgentSettings agent = anthropicAgent("dataset_list", "timeseries_get", "unit_list");

        // unit_list is on the agent's list but nothing advertises it this turn — a server being
        // down must not conjure a tool into existence.
        assertThat(allowedNames(agent, readsEverything()))
                .containsExactly("dataset_list", "timeseries_get");
    }

    @Test
    void dropsAnAdvertisedToolTheAgentDoesNotName() {
        // Default-deny. A tool added to datahub-api tomorrow does not become usable by an existing
        // agent until someone adds it to that agent's allowlist.
        assertThat(allowedNames(anthropicAgent("dataset_list"), readsEverything()))
                .containsExactly("dataset_list");
    }

    @Test
    void anAgentWithAnEmptyAllowlistGetsNothing() {
        // Empty means no tools. It must never be read as "unset, so all of them".
        assertThat(allowedNames(anthropicAgent(), readsEverything())).isEmpty();
    }

    @Test
    void aCallerWhoCanReadNothingIsOfferedNothing() {
        // Every one of these would be denied by datahub-api anyway. Offering them produces a turn
        // that looks like a broken assistant rather than a plain "you have no data access".
        assertThat(allowedNames(anthropicAgent("dataset_list", "event_search"), readsNothing()))
                .isEmpty();
    }

    @Test
    void anUnknownIdentityIsTreatedAsHavingNothing() {
        // If permissions could not be established, the safe reading is "none", never "all".
        assertThat(allowedNames(anthropicAgent("dataset_list"), null)).isEmpty();
    }

    @Test
    void aCallerWithGrantsOnSomeDatasetsKeepsTheReadTools() {
        // Per-dataset narrowing is datahub-api's job, per row, on the request that reads it. The
        // tool itself is still worth offering — it takes filters.
        assertThat(allowedNames(anthropicAgent("dataset_list", "event_search"), reads(1L, 2L)))
                .containsExactly("dataset_list", "event_search");
    }

    @Test
    void theExecutionCheckAgreesWithWhatWasOffered() {
        // A tool call is model output, so it is checked again immediately before it runs — with the
        // same predicate, so the two can never drift apart.
        AgentSettings agent = anthropicAgent("dataset_list");

        assertThat(policy.isAllowed("dataset_list", agent, readsEverything())).isTrue();
        assertThat(policy.isAllowed("dataset_delete", agent, readsEverything())).isFalse();
        assertThat(policy.isAllowed("dataset_list", agent, readsNothing())).isFalse();
        assertThat(policy.isAllowed("dataset_list", agent, null)).isFalse();
    }

    @Test
    void aMutatingToolCannotBeReachedEvenIfItSomehowReachesTheAllowlist() {
        // datahub-api refuses to store a mutating tool in an allowlist, so this should be
        // unreachable. Asserting it here means the console does not rely on that being true.
        AgentSettings agent = anthropicAgent("dataset_list");

        assertThat(policy.isAllowed("resource_delete", agent, readsEverything())).isFalse();
        assertThat(policy.isAllowed("timeseries_send_datapoint", agent, readsEverything())).isFalse();
    }
}
