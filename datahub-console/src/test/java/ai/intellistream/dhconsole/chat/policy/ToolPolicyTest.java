// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.dhconsole.chat.policy;

import ai.intellistream.dhconsole.chat.llm.LlmToolDef;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ToolPolicyTest {

    private final ToolPolicy policy = new ToolPolicy();

    /**
     * Pins the allowlist to the exact 20 read-only tools the platform's MCP servers advertise
     * (19 from datahub-api, analysis_related_series from datahub-analysis), so widening it is
     * a deliberate edit to this test rather than a quiet change in behaviour.
     */
    @Test
    void allowlistIsExactlyTheReadOnlyTools() {
        assertThat(policy.readOnlyToolNames()).containsExactlyInAnyOrder(
                "analysis_related_series",
                "dataset_list", "dataset_search",
                "edge_get", "edge_list_types",
                "event_filter", "event_get", "event_search",
                "label_list",
                "resource_fetch_nearest", "resource_fetch_related", "resource_get", "resource_search",
                "timeseries_fetch_datapoints", "timeseries_get", "timeseries_get_latest",
                "timeseries_list", "timeseries_search",
                "unit_get", "unit_list");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "dataset_create", "dataset_update", "dataset_delete",
            "resource_create", "resource_update", "resource_delete",
            "timeseries_create", "timeseries_update", "timeseries_delete",
            "event_create", "event_update", "event_delete",
            "edge_create", "edge_delete",
            "label_create", "label_update",
            // The two that do not follow the _create/_update/_delete naming and are easy to miss.
            "edge_create_type", "timeseries_send_datapoint"})
    void everyMutatingToolIsExcluded(String toolName) {
        assertThat(policy.isReadOnly(toolName)).isFalse();
    }

    @Test
    void anUnrecognisedToolIsTreatedAsMutating() {
        // A tool added to datahub-api tomorrow must not become usable here by default.
        assertThat(policy.isReadOnly("edge_merge_v2")).isFalse();
    }

    @Test
    void selectAllowedDropsAnythingNotOnTheAllowlist() {
        List<LlmToolDef> advertised = List.of(
                new LlmToolDef("dataset_list", "Browse datasets.", "{}"),
                new LlmToolDef("dataset_delete", "Delete a dataset.", "{}"),
                new LlmToolDef("something_new", "Who knows.", "{}"));

        assertThat(policy.selectAllowed(advertised))
                .extracting(LlmToolDef::name)
                .containsExactly("dataset_list");
    }
}
