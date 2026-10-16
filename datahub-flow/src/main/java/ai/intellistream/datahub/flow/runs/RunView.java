// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.flow.runs;

import ai.intellistream.datahub.flow.api.TimeWindow;
import ai.intellistream.datahub.flow.flows.FlowRepository.Deployment;
import ai.intellistream.datahub.flow.store.RunStore.StoredRun;
import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A run as the REST interface shows it: what it covers, what it ran with, how it ended.
 *
 * @param parameters        the values it was started with until it has run, then every parameter as
 *                          resolved
 * @param processorVersions every processor type in its flow with the version that ran
 * @param stats             the record count, the failing processor if any, and the run's log
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RunView(UUID id, String flow, Integer version, String status, int attempt,
                      int maxAttempts, String trigger, String triggeredBy, String runAs,
                      boolean preview, Window window, JsonNode parameters, JsonNode processorVersions,
                      String errorCode, String error, JsonNode stats, boolean cancelRequested,
                      Instant notBefore, Instant createdAt, Instant startedAt, Instant finishedAt) {

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
                run.startedAt(), run.finishedAt());
    }

    /**
     * One deployed flow on the Runs overview: how healthy it looks, and why.
     *
     * @param health  {@code PROBLEM}: something is definitely wrong; {@code WARNING}: it may be;
     *                {@code OK}; {@code OFF}: switched off by a person
     * @param reasons what made it a problem or a warning: {@code LAST_RUN_FAILED},
     *                {@code NEEDS_REAUTHORISATION}, {@code SWITCHED_OFF}, {@code BACKLOG},
     *                {@code RETRYING} or {@code OVERDUE}
     * @param waitingSince when the oldest run that waits to start was queued, which is how far
     *                     behind the flow is
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record BoardEntry(String flow, String name, int version, String health, List<String> reasons,
                             boolean enabled, String disabledReason, Instant nextDueAt, int running,
                             String runningOn, Instant runningSince, int queued, Instant waitingSince,
                             LastRun lastRun) {
    }

    /** A flow's latest finished run. */
    public record LastRun(UUID id, String status, String errorCode, Instant finishedAt) {
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
