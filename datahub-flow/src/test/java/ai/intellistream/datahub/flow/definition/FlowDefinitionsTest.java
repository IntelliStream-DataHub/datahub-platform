// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.flow.definition;

import ai.intellistream.datahub.flow.fake.ExampleFlow;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FlowDefinitionsTest {

    @Test
    void readsTheExampleFlow() {
        FlowDefinition d = FlowDefinitions.parse(ExampleFlow.DEFINITION);

        assertThat(d.schemaVersion()).isEqualTo(1);
        assertThat(d.externalId()).isEqualTo("temp_hourly");
        assertThat(d.parameters()).containsKeys("window", "limit");
        assertThat(d.parameters().get("limit").defaultValue().decimalValue()).isEqualByComparingTo("35");
        assertThat(d.processors()).extracting(FlowDefinition.ProcessorSpec::id)
                .containsExactly("src", "f", "out", "over", "ev");
        assertThat(d.processors().get(2).retry().maxAttempts()).isEqualTo(3);
        assertThat(d.processors().get(3).terminate()).containsExactly("success");
        assertThat(d.connections()).hasSize(4);
        assertThat(d.trigger().delay()).isEqualTo("PT5M");
        assertThat(d.execution().timeout()).isEqualTo("PT15M");
    }

    @Test
    void reportsEveryUnknownFieldWithItsPointer() {
        ObjectNode tree = ExampleFlow.tree();
        tree.put("owner", "someone");
        ((ObjectNode) tree.get("processors").get(2)).putObject("retries").put("maxAttempts", 5);
        ((ObjectNode) tree.get("connections").get(1)).put("weight", 2);

        assertThatThrownBy(() -> FlowDefinitions.parse(tree.toString()))
                .isInstanceOfSatisfying(InvalidFlowException.class, e ->
                        assertThat(e.problems()).extracting(ValidationProblem::pointer)
                                .containsExactlyInAnyOrder("#/owner", "#/processors/2/retries",
                                        "#/connections/1/weight"));
    }

    @Test
    void propertyNamesAreTheAuthorsToChoose() {
        // A processor's properties map is open: the validator, not the parser, judges its keys.
        ObjectNode tree = ExampleFlow.tree();
        ((ObjectNode) tree.get("processors").get(4).get("properties")).put("site", "Oslo");

        assertThat(FlowDefinitions.parse(tree.toString()).processors().get(4).properties())
                .containsKey("site");
    }

    @Test
    void aWrongTypeIsReportedWhereItIs() {
        ObjectNode tree = ExampleFlow.tree();
        ((ObjectNode) tree.get("execution")).put("maxAttempts", "two");

        assertThatThrownBy(() -> FlowDefinitions.parse(tree.toString()))
                .isInstanceOfSatisfying(InvalidFlowException.class, e ->
                        assertThat(e.problems()).singleElement().satisfies(p ->
                                assertThat(p.pointer()).isEqualTo("#/execution/maxAttempts")));
    }

    @Test
    void trailingContentIsRejected() {
        assertThatThrownBy(() -> FlowDefinitions.parse(ExampleFlow.DEFINITION + "{}"))
                .isInstanceOf(InvalidFlowException.class);
    }
}
