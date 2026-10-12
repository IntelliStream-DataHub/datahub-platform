// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.flow.engine;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * How one attempt of a run ended, and what it ran with — the values that make it reproducible.
 *
 * @param errorCode         null on success; otherwise one of the constants here
 * @param failedProcessor   the processor that failed it, when one did
 * @param parameters        every parameter as resolved for the run
 * @param secretsUsed       the names of the secrets it read, never their values
 * @param processorVersions every processor type in the flow with its version
 * @param records           how many records the run produced
 */
public record RunResult(Status status, String errorCode, String error, String failedProcessor,
                        Map<String, Object> parameters, Set<String> secretsUsed,
                        Map<String, String> processorVersions, List<LogLine> log, long records) {

    /** A processor threw on every attempt. */
    public static final String PROCESSOR_FAILED = "PROCESSOR_FAILED";
    /** An item was sent to a relationship that fails the run when left unconnected. */
    public static final String ITEM_FAILED = "ITEM_FAILED";
    /** The run went over its memory cap. */
    public static final String MEMORY_LIMIT = "MEMORY_LIMIT";
    /** A processor was invoked with items waiting and took none. */
    public static final String NO_PROGRESS = "NO_PROGRESS";
    /** A parameter was missing or did not fit its type, or a run-level reference had no value. */
    public static final String INVALID_PARAMETERS = "INVALID_PARAMETERS";
    /** The flow refers to a secret the tenant does not have. */
    public static final String MISSING_SECRET = "MISSING_SECRET";
    /** It was cancelled, ran past its timeout, or lost its lease. */
    public static final String CANCELLED = "CANCELLED";

    public enum Status { SUCCEEDED, FAILED, CANCELLED }

    /** A line on the run's page. {@code processorId} is null for the engine's own lines. */
    public record LogLine(Instant time, String processorId, String level, String message) {
    }
}
