// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.flow.config;

import ai.intellistream.datahub.flow.definition.FlowDefinitions;
import ai.intellistream.datahub.flow.definition.FlowValidator;
import ai.intellistream.datahub.flow.definition.ValidationProblem;
import ai.intellistream.datahub.flow.fake.ExampleFlow;
import ai.intellistream.datahub.flow.fake.FakeProcessors;
import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FlowConfigTest {

    private static final long GB = DataSize.ofGigabytes(1).toBytes();

    @Test
    void unsetTheBudgetIsHalfTheHeap() {
        assertThat(FlowConfig.runMemoryBudget("", 4 * GB)).isEqualTo(DataSize.ofGigabytes(2));
    }

    @Test
    void aBudgetOverHalfTheHeapStopsTheBoot() {
        assertThatThrownBy(() -> FlowConfig.runMemoryBudget("3GB", 4 * GB))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("more than half the maximum heap");
    }

    @Test
    void aBudgetTooSmallForTheDefaultCapStopsTheBoot() {
        assertThatThrownBy(() -> FlowConfig.runMemoryBudget("256MB", 4 * GB))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no run would ever start");
        assertThatThrownBy(() -> FlowConfig.runMemoryBudget("", 512 * 1024 * 1024))
                .hasMessageContaining("no run would ever start");
    }

    @Test
    void aRunMayAskForNoMoreThanTheBudget() {
        FlowValidator validator = new FlowConfig().flowValidator(FakeProcessors.catalog(), DataSize.ofGigabytes(1));
        assertThat(problems(validator, "1GB")).isEmpty();
        assertThat(problems(validator, "2GB")).extracting(ValidationProblem::pointer)
                .containsExactly("#/execution/memory");
    }

    private static List<ValidationProblem> problems(FlowValidator validator, String memory) {
        ObjectNode tree = ExampleFlow.tree();
        ((ObjectNode) tree.get("execution")).put("memory", memory);
        return validator.validate(FlowDefinitions.parse(tree.toString()), null);
    }
}
