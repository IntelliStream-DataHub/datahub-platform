// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.flow.config;

import ai.intellistream.datahub.flow.api.Processor;
import ai.intellistream.datahub.flow.definition.FlowValidator;
import ai.intellistream.datahub.flow.engine.FlowCompiler;
import ai.intellistream.datahub.flow.engine.RunExecutor;
import ai.intellistream.datahub.flow.runs.RunService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.unit.DataSize;

import java.time.Clock;
import java.util.List;

/**
 * The validator, over every {@link Processor} bean in the service: what a definition may use; the
 * engine that compiles and executes a run; and the memory budget the runs share.
 */
@Configuration
public class FlowConfig {

    /**
     * What the runs on this instance may hold at once: each reserves its memory cap out of it
     * before it starts. Runs hold their data on the heap, so the budget leaves the other half of it
     * to everything else: unset, it is half the maximum heap, and a larger one stops the boot. So
     * does one that cannot hold a single run with the default cap, since no run would ever start.
     */
    @Bean
    DataSize runMemoryBudget(@Value("${datahub.flow.runs.memory-budget:}") String configured) {
        return runMemoryBudget(configured, Runtime.getRuntime().maxMemory());
    }

    static DataSize runMemoryBudget(String configured, long maxHeap) {
        long half = maxHeap / 2;
        long budget = configured == null || configured.isBlank() ? half : DataSize.parse(configured).toBytes();
        if (budget > half) {
            throw new IllegalStateException("datahub.flow.runs.memory-budget (" + mb(budget) + " MB) is more "
                    + "than half the maximum heap (" + mb(maxHeap) + " MB). Raise -Xmx or lower the budget.");
        }
        if (RunService.DEFAULT_MEMORY > budget) {
            throw new IllegalStateException("The run memory budget (" + mb(budget) + " MB) cannot hold one "
                    + "run with the default memory cap of " + mb(RunService.DEFAULT_MEMORY) + " MB, so no run "
                    + "would ever start. Raise -Xmx.");
        }
        return DataSize.ofBytes(budget);
    }

    private static long mb(long bytes) {
        return DataSize.ofBytes(bytes).toMegabytes();
    }

    /** A run's cap above the budget would pass validation and then never be claimed anywhere. */
    @Bean
    FlowValidator flowValidator(List<Processor> processors, DataSize runMemoryBudget) {
        FlowValidator.Limits defaults = FlowValidator.Limits.DEFAULTS;
        return new FlowValidator(processors, new FlowValidator.Limits(defaults.maxTimeout(),
                Math.min(defaults.maxMemoryBytes(), runMemoryBudget.toBytes())));
    }

    @Bean
    FlowCompiler flowCompiler(FlowValidator validator) {
        return new FlowCompiler(validator);
    }

    @Bean
    RunExecutor runExecutor() {
        return new RunExecutor(Clock.systemUTC(), Thread::sleep);
    }
}
