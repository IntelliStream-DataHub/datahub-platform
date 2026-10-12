// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.flow.config;

import ai.intellistream.datahub.flow.api.Processor;
import ai.intellistream.datahub.flow.definition.FlowValidator;
import ai.intellistream.datahub.flow.engine.FlowCompiler;
import ai.intellistream.datahub.flow.engine.RunExecutor;
import org.apache.arrow.memory.BufferAllocator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.util.List;

/**
 * The validator, over every {@link Processor} bean in the service: what a definition may use; and
 * the engine that compiles and executes a run, its memory coming out of the instance's Arrow budget.
 */
@Configuration
public class FlowConfig {

    @Bean
    FlowValidator flowValidator(List<Processor> processors) {
        return new FlowValidator(processors, FlowValidator.Limits.DEFAULTS);
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
