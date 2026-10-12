// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.flow.flows;

import ai.intellistream.datahub.flow.definition.FlowDefinitions;
import ai.intellistream.datahub.flow.definition.ValidationProblem;
import ai.intellistream.datahub.flow.flows.DeclaredSeries.Series;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Which series a flow's sinks declare with {@code create}, worked out from the definition alone. */
class DeclaredSeriesTest {

    @Test
    void aTargetBuiltFromTheSeriesNameMakesOneSeriesPerUpstreamSourceSeries() {
        DeclaredSeries.Result result = DeclaredSeries.of(FlowDefinitions.parse(TestFlows.EXAMPLE), "ds_demo");

        assertThat(result.problems()).isEmpty();
        assertThat(result.series()).containsExactly(
                new Series("ts_out_temp_f", "ts_out_temp in °F", "°F", null, "ds_demo"),
                new Series("ts_in_temp_f", "ts_in_temp in °F", "°F", null, "ds_demo"));
    }

    @Test
    void aFixedTargetIsOneSeriesInTheDatasetTheSinkNames() {
        String flow = TestFlows.EXAMPLE
                .replace("\"target\": \"${timeseries.externalId}_f\"", "\"target\": \"total_f\"")
                .replace("\"unit\": \"°F\"", "\"unit\": \"°F\", \"dataSet\": \"ds_other\"");

        DeclaredSeries.Result result = DeclaredSeries.of(FlowDefinitions.parse(flow), "ds_demo");

        assertThat(result.series()).extracting(Series::externalId, Series::dataSet)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("total_f", "ds_other"));
    }

    @Test
    void aSinkWithoutCreateDeclaresNothing() {
        String flow = TestFlows.EXAMPLE.replace("\"create\": true", "\"create\": false");

        assertThat(DeclaredSeries.of(FlowDefinitions.parse(flow), "ds_demo").series()).isEmpty();
    }

    @Test
    void aTargetThatDependsOnARunIsRefused() {
        String flow = TestFlows.EXAMPLE.replace("\"target\": \"${timeseries.externalId}_f\"",
                "\"target\": \"${parameters.prefix}_f\"");

        DeclaredSeries.Result result = DeclaredSeries.of(FlowDefinitions.parse(flow), "ds_demo");

        assertThat(result.series()).isEmpty();
        assertThat(result.problems()).extracting(ValidationProblem::pointer)
                .containsExactly("#/processors/2/properties/target");
    }

    @Test
    void aSourceListThatIsNotFixedIsRefused() {
        String flow = TestFlows.EXAMPLE.replace("[\"ts_out_temp\", \"ts_in_temp\"]",
                "[\"ts_out_temp\", \"${parameters.extra}\"]");

        assertThat(DeclaredSeries.of(FlowDefinitions.parse(flow), "ds_demo").problems())
                .extracting(ValidationProblem::pointer)
                .containsExactly("#/processors/0/properties/timeseries");
    }

    @Test
    void createWithoutAUnitIsRefused() {
        String flow = TestFlows.EXAMPLE.replace(", \"unit\": \"°F\"", "");

        assertThat(DeclaredSeries.of(FlowDefinitions.parse(flow), "ds_demo").problems())
                .extracting(ValidationProblem::pointer)
                .containsExactly("#/processors/2/properties/unit");
    }

    @Test
    void aReferenceToTheSeriesWithNoSourceUpstreamIsRefused() {
        String flow = TestFlows.EXAMPLE.replace(
                "{ \"from\": \"src\",  \"relationship\": \"success\", \"to\": \"f\" },", "");

        assertThat(DeclaredSeries.of(FlowDefinitions.parse(flow), "ds_demo").problems())
                .extracting(ValidationProblem::pointer)
                .containsExactly("#/processors/2/properties/target");
    }
}
