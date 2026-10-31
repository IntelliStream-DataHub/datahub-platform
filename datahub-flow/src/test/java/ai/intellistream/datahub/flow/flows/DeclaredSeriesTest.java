// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.flow.flows;

import ai.intellistream.datahub.flow.api.ProcessorDescriptor;
import ai.intellistream.datahub.flow.definition.FlowDefinitions;
import ai.intellistream.datahub.flow.definition.ValidationProblem;
import ai.intellistream.datahub.flow.flows.DeclaredSeries.Series;
import ai.intellistream.datahub.flow.processors.TimeseriesSink;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Which series a flow's sinks declare with {@code create}, worked out from the definition alone. */
class DeclaredSeriesTest {

    private static final ProcessorDescriptor SINK = new TimeseriesSink().describe();

    @Test
    void aTargetBuiltFromTheSeriesNameMakesOneSeriesPerUpstreamSourceSeries() {
        DeclaredSeries.Result result = DeclaredSeries.of(FlowDefinitions.parse(TestFlows.EXAMPLE), "ds_demo", SINK);

        assertThat(result.problems()).isEmpty();
        assertThat(result.series()).containsExactly(
                new Series("ts_out_temp_f", "ts_out_temp in °F", "°F", null, null, "ds_demo"),
                new Series("ts_in_temp_f", "ts_in_temp in °F", "°F", null, null, "ds_demo"));
    }

    @Test
    void aFixedTargetIsOneSeriesInTheDatasetTheSinkNames() {
        String flow = TestFlows.EXAMPLE
                .replace("\"target\": \"${timeseries.externalId}_f\"", "\"target\": \"total_f\"")
                .replace("\"unit\": \"°F\"", "\"unit\": \"°F\", \"dataSet\": \"ds_other\"");

        DeclaredSeries.Result result = DeclaredSeries.of(FlowDefinitions.parse(flow), "ds_demo", SINK);

        assertThat(result.series()).extracting(Series::externalId, Series::dataSet)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("total_f", "ds_other"));
    }

    @Test
    void aSinkWithoutCreateDeclaresNothing() {
        String flow = TestFlows.EXAMPLE.replace("\"create\": true", "\"create\": false");

        assertThat(DeclaredSeries.of(FlowDefinitions.parse(flow), "ds_demo", SINK).series()).isEmpty();
    }

    @Test
    void aTargetThatDependsOnARunIsRefused() {
        String flow = TestFlows.EXAMPLE.replace("\"target\": \"${timeseries.externalId}_f\"",
                "\"target\": \"${parameters.prefix}_f\"");

        DeclaredSeries.Result result = DeclaredSeries.of(FlowDefinitions.parse(flow), "ds_demo", SINK);

        assertThat(result.series()).isEmpty();
        assertThat(result.problems()).extracting(ValidationProblem::pointer)
                .containsExactly("#/processors/2/properties/target");
    }

    @Test
    void aSourceThatIsAReferenceIsRefused() {
        String flow = TestFlows.EXAMPLE.replace("\"ts_out_temp\" }", "\"${parameters.extra}\" }");

        assertThat(DeclaredSeries.of(FlowDefinitions.parse(flow), "ds_demo", SINK).problems())
                .extracting(ValidationProblem::pointer)
                .containsExactly("#/processors/0/properties/timeseries");
    }

    @Test
    void aSinkWithNothingSetCreatesASeriesBesideEachOneReadWithItsUnit() {
        String flow = TestFlows.EXAMPLE.replace(
                "{ \"target\": \"${timeseries.externalId}_f\", \"create\": true,", "{")
                .replace("\"name\": \"${timeseries.externalId} in °F\", \"unit\": \"°F\" }", "}");

        DeclaredSeries.Result result = DeclaredSeries.of(FlowDefinitions.parse(flow), "ds_demo", SINK);

        assertThat(result.problems()).isEmpty();
        assertThat(result.series()).containsExactly(
                new Series("ts_out_temp_out", "ts_out_temp_out", null, "ts_out_temp", null, "ds_demo"),
                new Series("ts_in_temp_out", "ts_in_temp_out", null, "ts_in_temp", null, "ds_demo"));
    }

    @Test
    void aFixedTargetWithoutAUnitIsRefused() {
        String flow = TestFlows.EXAMPLE.replace(", \"unit\": \"°F\"", "")
                .replace("\"target\": \"${timeseries.externalId}_f\"", "\"target\": \"total_f\"");

        assertThat(DeclaredSeries.of(FlowDefinitions.parse(flow), "ds_demo", SINK).problems())
                .extracting(ValidationProblem::pointer)
                .containsExactly("#/processors/2/properties/unit");
    }

    @Test
    void aReferenceToTheSeriesWithNoSourceUpstreamIsRefused() {
        String flow = TestFlows.EXAMPLE
                .replace("{ \"from\": \"src\",  \"relationship\": \"success\", \"to\": \"f\" },", "")
                .replace("{ \"from\": \"src2\", \"relationship\": \"success\", \"to\": \"f\" },", "");

        assertThat(DeclaredSeries.of(FlowDefinitions.parse(flow), "ds_demo", SINK).problems())
                .extracting(ValidationProblem::pointer)
                .containsExactly("#/processors/2/properties/target");
    }
}
