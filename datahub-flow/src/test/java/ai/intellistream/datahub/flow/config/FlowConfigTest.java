// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.flow.config;

import ai.intellistream.datahub.flow.definition.FlowDefinitions;
import ai.intellistream.datahub.flow.definition.FlowValidator;
import ai.intellistream.datahub.flow.definition.ValidationProblem;
import ai.intellistream.datahub.flow.fake.ExampleFlow;
import ai.intellistream.datahub.flow.fake.FakeProcessors;
import org.apache.arrow.memory.RootAllocator;
import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FlowConfigTest {

    @Test
    void aBudgetTooSmallForTheDefaultCapStopsTheBoot() {
        try (RootAllocator allocator = new RootAllocator(DataSize.ofMegabytes(256).toBytes())) {
            assertThatThrownBy(() -> new FlowConfig().flowValidator(List.of(), allocator))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("no run would ever start");
        }
    }

    @Test
    void aRunMayAskForNoMoreThanTheBudget() {
        try (RootAllocator allocator = new RootAllocator(DataSize.ofGigabytes(1).toBytes())) {
            FlowValidator validator = new FlowConfig().flowValidator(FakeProcessors.catalog(), allocator);
            assertThat(problems(validator, "1GB")).isEmpty();
            assertThat(problems(validator, "2GB")).extracting(ValidationProblem::pointer)
                    .containsExactly("#/execution/memory");
        }
    }

    private static List<ValidationProblem> problems(FlowValidator validator, String memory) {
        ObjectNode tree = ExampleFlow.tree();
        ((ObjectNode) tree.get("execution")).put("memory", memory);
        return validator.validate(FlowDefinitions.parse(tree.toString()), null);
    }
}
