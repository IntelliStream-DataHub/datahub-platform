// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.dhconsole.chat.agent;

import ai.intellistream.dhconsole.chat.llm.LlmBlock;
import ai.intellistream.dhconsole.chat.llm.LlmToolDef;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ConsoleViewsTest {

    private final ConsoleViews consoleViews = new ConsoleViews(JsonMapper.builder().build());

    private static LlmBlock.ToolResult ok(String json) {
        return new LlmBlock.ToolResult("t1", json, false);
    }

    private static LlmBlock.ToolUse eventFilter(Map<String, Object> args) {
        return new LlmBlock.ToolUse("t1", "event_filter", args);
    }

    @Test
    void mapsAnEventFilterCallToAnEventsView() {
        var view = consoleViews.from(
                eventFilter(Map.of("type", "ALARM", "start", "2026-08-01T00:00:00Z")),
                ok("{\"returned\":5,\"limit\":25,\"events\":[]}"));

        assertThat(view).isPresent();
        assertThat(view.get().page()).isEqualTo("events");
        assertThat(view.get().count()).isEqualTo(5);
        // The filter is exactly what the model asked for — that is the point: the view cannot
        // disagree with the answer it accompanies.
        assertThat(view.get().filter())
                .containsEntry("type", "ALARM")
                .containsEntry("start", "2026-08-01T00:00:00Z");
    }

    @Test
    void dropsArgumentsThatShapeTheAnswerRatherThanTheFilter() {
        var view = consoleViews.from(
                eventFilter(Map.of("type", "ALARM", "groupBy", "subType", "limit", 25)),
                ok("{\"groupedBy\":\"subType\",\"total\":12,\"buckets\":[]}"));

        assertThat(view).isPresent();
        assertThat(view.get().filter()).containsOnlyKeys("type");
        // Aggregate results count with 'total' rather than 'returned'.
        assertThat(view.get().count()).isEqualTo(12);
    }

    @Test
    void ignoresToolsThatHaveNoPage() {
        assertThat(consoleViews.from(
                new LlmBlock.ToolUse("t1", "dataset_list", Map.of()), ok("{}")))
                .isEmpty();
    }

    @Test
    void ignoresAFailedLookup() {
        // Offering to open something the lookup could not produce would be a dead end.
        assertThat(consoleViews.from(
                eventFilter(Map.of("type", "ALARM")),
                new LlmBlock.ToolResult("t1", "Neo4j unavailable", true)))
                .isEmpty();
    }

    @Test
    void ignoresAnUnfilteredCallBecauseThePageAlreadyShowsThat() {
        assertThat(consoleViews.from(eventFilter(Map.of("limit", 25)), ok("{\"returned\":25}")))
                .isEmpty();
    }

    @Test
    void survivesAResultTruncatedIntoInvalidJson() {
        // AgentRunner caps tool results by characters, which can cut the JSON mid-object. The count
        // is cosmetic, so the view still stands.
        var view = consoleViews.from(
                eventFilter(Map.of("type", "ALARM")),
                ok("{\"returned\":5,\"events\":[{\"id\":\"1\""));

        assertThat(view).isPresent();
        assertThat(view.get().count()).isNull();
    }

    @Test
    void blankArgumentsAreNotTreatedAsFilters() {
        assertThat(consoleViews.from(
                eventFilter(Map.of("type", "ALARM", "source", "  ")), ok("{}")))
                .get()
                .satisfies(view -> assertThat(view.filter()).containsOnlyKeys("type"));
    }

    @Test
    void carriesTheDatasetNarrowingOverToThePagesOwnField() {
        // The api keeps ids and externalIds apart (an all-digit externalId is legal); the page has
        // one comma-separated field that resolves either. Without the join the view was wider than
        // the answer it accompanied.
        var view = consoleViews.from(
                eventFilter(Map.of(
                        "type", "ALARM",
                        "dataSetId", List.of("43", "44"),
                        "dataSetExternalId", List.of("data_set_sap"))),
                ok("{\"returned\":3}"));

        assertThat(view).get().satisfies(v -> assertThat(v.filter())
                .containsEntry("type", "ALARM")
                .containsEntry("dataSetId", "43,44,data_set_sap"));
    }

    @Test
    void keepsDatasetIdsAsWrittenRatherThanParsingThem() {
        // The join is textual: an id read as a number and written back out can only lose on the way
        // through, silently once it passes 2^53.
        var view = consoleViews.from(
                eventFilter(Map.of("dataSetId", List.of("9223372036854775806"))),
                ok("{\"returned\":1}"));

        assertThat(view).get().satisfies(v -> assertThat(v.filter())
                .containsEntry("dataSetId", "9223372036854775806"));
    }

    @Test
    void aDatasetOnlyQueryStillOffersAView() {
        // The dataset narrowing is the whole filter here; dropping it would leave an "everything"
        // view, which is exactly the case from() refuses to offer.
        assertThat(consoleViews.from(
                eventFilter(Map.of("dataSetExternalId", List.of("data_set_sap"))), ok("{}")))
                .isPresent();
    }

    @Test
    void advertisesEveryLocalNavigationTool() {
        assertThat(consoleViews.localToolDefs()).extracting(LlmToolDef::name)
                .containsExactlyInAnyOrder(
                        "open_events_view", "open_resources_view", "open_insights_view", "open_analyze_view");
        assertThat(consoleViews.isLocalTool("open_analyze_view")).isTrue();
        assertThat(consoleViews.isLocalTool("event_filter")).isFalse();
    }

    @Test
    void buildsAnInsightsViewWithSeveralSeriesAndAWindow() {
        var view = consoleViews.fromLocalCall(new LlmBlock.ToolUse("t1", "open_insights_view", Map.of(
                "externalIds", List.of("21-PT-1234", "21-TT-1234"),
                "start", "2026-08-01T00:00:00Z",
                "end", "2026-08-03T00:00:00Z")));

        assertThat(view).isPresent();
        assertThat(view.get().page()).isEqualTo("insights");
        assertThat(view.get().filter())
                .containsEntry("externalIds", List.of("21-PT-1234", "21-TT-1234"))
                .containsEntry("start", "2026-08-01T00:00:00Z")
                .containsEntry("end", "2026-08-03T00:00:00Z");
    }

    @Test
    void buildsAResourcesSearchView() {
        var view = consoleViews.fromLocalCall(new LlmBlock.ToolUse(
                "t1", "open_resources_view", Map.of("query", "pump 21")));

        assertThat(view).isPresent();
        assertThat(view.get().page()).isEqualTo("resources");
        assertThat(view.get().filter()).containsEntry("query", "pump 21");
    }

    @Test
    void buildsAnAnalyzeViewWithSeedAndWindow() {
        var view = consoleViews.fromLocalCall(new LlmBlock.ToolUse("t1", "open_analyze_view", Map.of(
                "focusExternalId", "21-PT-1234",
                "start", "2026-08-01T00:00:00Z",
                "end", "2026-08-03T00:00:00Z",
                "limit", 15)));

        assertThat(view).isPresent();
        assertThat(view.get().page()).isEqualTo("analyze");
        assertThat(view.get().filter())
                .containsEntry("focusExternalId", "21-PT-1234")
                .containsEntry("start", "2026-08-01T00:00:00Z")
                .containsEntry("end", "2026-08-03T00:00:00Z")
                .containsEntry("limit", 15);
    }

    @Test
    void buildsAViewFromAnExplicitOpenEventsViewCall() {
        var view = consoleViews.fromLocalCall(new LlmBlock.ToolUse(
                "t1", "open_events_view", Map.of("type", "ALARM", "source", "SCADA")));

        assertThat(view).isPresent();
        assertThat(view.get().page()).isEqualTo("events");
        // Nothing was queried, so there is no count.
        assertThat(view.get().count()).isNull();
        assertThat(view.get().filter()).containsEntry("type", "ALARM").containsEntry("source", "SCADA");
    }

    @Test
    void anUnfilteredNavigationIsStillHonoured() {
        // Unlike the auto-offer, an explicit "open the events page" with no filter is a valid request
        // to go there — the user asked for it, so the view stands even empty.
        var view = consoleViews.fromLocalCall(new LlmBlock.ToolUse("t1", "open_events_view", Map.of()));

        assertThat(view).isPresent();
        assertThat(view.get().filter()).isEmpty();
    }
}
