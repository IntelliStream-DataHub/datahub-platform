// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.flow.config;

import ai.intellistream.datahub.flow.api.Processor;
import ai.intellistream.datahub.flow.definition.FlowValidator;
import ai.intellistream.datahub.flow.engine.FlowCompiler;
import ai.intellistream.datahub.flow.engine.RunExecutor;
import ai.intellistream.datahub.flow.runs.RunService;
import org.apache.arrow.memory.BufferAllocator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.unit.DataSize;

import java.time.Clock;
import java.util.List;

/**
 * The validator, over every {@link Processor} bean in the service: what a definition may use; and
 * the engine that compiles and executes a run, its memory coming out of the instance's Arrow budget.
 */
@Configuration
public class FlowConfig {

    /**
     * A run's memory cap is reserved out of one instance's Arrow budget before it starts, so a cap
     * above the budget would pass validation and then never be claimed anywhere: the validator's
     * limit is the budget, and a budget that cannot hold the default cap stops the boot.
     */
    @Bean
    FlowValidator flowValidator(List<Processor> processors, BufferAllocator rootAllocator) {
        long budget = rootAllocator.getLimit();
        if (RunService.DEFAULT_MEMORY > budget) {
            throw new IllegalStateException("datahub.flow.arrow.budget (" + DataSize.ofBytes(budget).toMegabytes()
                    + " MB) cannot hold one run with the default memory cap of "
                    + DataSize.ofBytes(RunService.DEFAULT_MEMORY).toMegabytes() + " MB; no run would ever start.");
        }
        FlowValidator.Limits defaults = FlowValidator.Limits.DEFAULTS;
        return new FlowValidator(processors, new FlowValidator.Limits(defaults.maxTimeout(),
                Math.min(defaults.maxMemoryBytes(), budget)));
    }

    @Bean
    FlowCompiler flowCompiler(FlowValidator validator) {
        return new FlowCompiler(validator);
    }

    @Bean
    RunExecutor runExecutor(BufferAllocator rootAllocator) {
        return new RunExecutor(rootAllocator, Clock.systemUTC(), Thread::sleep);
    }
}
