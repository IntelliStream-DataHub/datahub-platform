// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.flow.config;

import ai.intellistream.datahub.flow.api.Processor;
import ai.intellistream.datahub.flow.definition.FlowValidator;
import ai.intellistream.datahub.flow.engine.FlowCompiler;
import ai.intellistream.datahub.flow.engine.RunExecutor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.unit.DataSize;

import java.time.Clock;
import java.util.List;

/**
 * The validator, over every {@link Processor} bean in the service: what a definition may use; the
 * engine that compiles and executes a run; and how much memory each run gets.
 */
@Slf4j
@Configuration
public class FlowConfig {

    /** Under this per run, most runs would fail on their cap, so the service refuses to start. */
    static final DataSize MIN_RUN_MEMORY = DataSize.ofMegabytes(64);

    /**
     * What one run may hold on the heap. Nobody sizes a flow: the operator sizes the instance, with
     * {@code -Xmx} and {@code max-running}, and every run gets an equal share of the run memory
     * budget, which is half the maximum heap unless {@code memory-budget} sets a smaller one. Runs
     * hold their data on the heap, so the other half is left to everything else: a budget over
     * half the heap stops the boot, and so does a share under {@link #MIN_RUN_MEMORY}.
     */
    @Bean
    DataSize runMemoryCap(@Value("${datahub.flow.runs.memory-budget:}") String configured,
                          @Value("${datahub.flow.runs.max-running:8}") int maxRunning) {
        return runMemoryCap(configured, Runtime.getRuntime().maxMemory(), maxRunning);
    }

    static DataSize runMemoryCap(String configured, long maxHeap, int maxRunning) {
        long half = maxHeap / 2;
        long budget = configured == null || configured.isBlank() ? half : DataSize.parse(configured).toBytes();
        if (budget > half) {
            throw new IllegalStateException("datahub.flow.runs.memory-budget (" + mb(budget) + " MB) is more "
                    + "than half the maximum heap (" + mb(maxHeap) + " MB). Raise -Xmx or lower the budget.");
        }
        long slots = Math.max(1, maxRunning);
        long cap = budget / slots;
        if (cap < MIN_RUN_MEMORY.toBytes()) {
            throw new IllegalStateException("The run memory budget (" + mb(budget) + " MB) split over "
                    + slots + " run slots gives each run " + mb(cap) + " MB, under the " + MIN_RUN_MEMORY.toMegabytes()
                    + " MB a run needs. Raise -Xmx or lower datahub.flow.runs.max-running.");
        }
        log.info("Each of the {} run slots gets {} MB of the {} MB run memory budget", slots, mb(cap), mb(budget));
        return DataSize.ofBytes(cap);
    }

    private static long mb(long bytes) {
        return DataSize.ofBytes(bytes).toMegabytes();
    }

    @Bean
    FlowValidator flowValidator(List<Processor> processors) {
        return new FlowValidator(processors, FlowValidator.Limits.DEFAULTS);
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
