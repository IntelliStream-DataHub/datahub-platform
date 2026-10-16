// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.flow.runs;

import ai.intellistream.datahub.flow.api.TimeWindow;
import ai.intellistream.datahub.flow.flows.FlowRepository.Deployment;
import ai.intellistream.datahub.flow.runs.RunRepository.StoredRun;
import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * A run as the REST interface shows it: what it covers, what it ran with, how it ended.
 *
 * @param parameters        the values it was started with until it has run, then every parameter as
 *                          resolved
 * @param processorVersions every processor type in its flow with the version that ran
 * @param stats             the record count, the failing processor if any, and the run's log
 * @param owner             the instance holding the run while it is running, null otherwise
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RunView(UUID id, String flow, Integer version, String status, int attempt,
                      int maxAttempts, String trigger, String triggeredBy, String runAs,
                      boolean preview, Window window, JsonNode parameters, JsonNode processorVersions,
                      String errorCode, String error, JsonNode stats, boolean cancelRequested,
                      Instant notBefore, Instant createdAt, Instant startedAt, Instant finishedAt,
                      String owner) {

    /** {@code [start, end)} in data time. */
    public record Window(Instant start, Instant end) {
    }

    public static RunView of(StoredRun run, JsonMapper json) {
        TimeWindow w = run.window();
        return new RunView(run.id(), run.flowExternalId(), run.version(), run.status(), run.attempt(),
                run.maxAttempts(), run.trigger(), run.triggeredBy(), run.runAs(), run.preview(),
                w == null ? null : new Window(w.start(), w.end()), tree(json, run.parameters()),
                tree(json, run.processorVersions()), run.errorCode(), run.error(),
                tree(json, run.stats()), run.cancelRequested(),
                "PENDING".equals(run.status()) ? run.notBefore() : null, run.createdAt(),
                run.startedAt(), run.finishedAt(), run.owner());
    }

    private static JsonNode tree(JsonMapper json, String text) {
        return text == null ? null : json.readTree(text);
    }

    /**
     * A flow's deployment as the REST interface shows it.
     *
     * @param runAs the identity runs act as: the deployer's subject, or a registered client's name
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record DeploymentView(int version, boolean enabled, String disabledReason, JsonNode trigger,
                                 int maxConcurrentRuns, int maxAttempts, Duration timeout,
                                 String identity, String runAs, String identityState,
                                 String identityStateReason, String deployedBy, Instant deployedAt,
                                 Instant nextTick, Instant nextDueAt, Instant lastTick) {

        static DeploymentView of(Deployment d, JsonMapper json) {
            return new DeploymentView(d.version(), d.enabled(), d.disabledReason(),
                    tree(json, d.trigger()), d.maxConcurrentRuns(), d.maxAttempts(), d.timeout(),
                    d.identityKind(),
                    d.identitySubject() != null ? d.identitySubject() : d.identityClientName(),
                    d.identityState(), d.identityStateReason(), d.deployedBy(), d.deployedAt(),
                    d.nextTick(), d.nextDueAt(), d.lastTick());
        }
    }
}
