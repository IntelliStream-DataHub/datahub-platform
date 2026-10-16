// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.flow.runs;

import ai.intellistream.datahub.api.responses.DataWrapper;
import ai.intellistream.datahub.flow.access.Caller;
import ai.intellistream.datahub.flow.access.CallerAccess;
import ai.intellistream.datahub.flow.api.TimeWindow;
import ai.intellistream.datahub.flow.definition.ConfiguredValue;
import ai.intellistream.datahub.flow.definition.FlowDefinition;
import ai.intellistream.datahub.flow.definition.FlowDefinitions;
import ai.intellistream.datahub.flow.engine.FlowCompiler;
import ai.intellistream.datahub.flow.flows.FlowRepository;
import ai.intellistream.datahub.flow.flows.FlowRepository.Deployment;
import ai.intellistream.datahub.flow.flows.FlowRepository.Identity;
import ai.intellistream.datahub.flow.flows.FlowRepository.IdentityState;
import ai.intellistream.datahub.flow.flows.FlowRepository.StoredFlow;
import ai.intellistream.datahub.flow.flows.FlowService;
import ai.intellistream.datahub.flow.identity.FlowTokens;
import ai.intellistream.datahub.flow.identity.RegisteredClients;
import ai.intellistream.datahub.flow.runs.RunRepository.NewRun;
import ai.intellistream.datahub.flow.runs.RunRepository.StoredRun;
import ai.intellistream.datahub.flow.runs.RunView.DeploymentView;
import ai.intellistream.datahub.flow.web.FlowProblems;
import ai.intellistream.datahub.models.paging.PageCursor;
import ai.intellistream.datahub.tenant.TenantContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Deploying flows and starting, reading and cancelling their runs, under the flows' access rules:
 * seeing a run takes what seeing its flow takes; deploying, starting, cancelling or re-running takes
 * {@code /flows/write} and write access to the flow's dataset.
 */
@Slf4j
@Service
public class RunService {

    static final int DEFAULT_LIMIT = 100;
    static final int MAX_LIMIT = 1000;
    private static final String CURSOR_PROPERTY = "createdAt";

    static final int DEFAULT_MAX_CONCURRENT_RUNS = 1;
    static final int DEFAULT_MAX_ATTEMPTS = 2;
    static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(15);

    /** A preview's window when none is given. */
    static final Duration PREVIEW_WINDOW = Duration.ofHours(1);
    /** How much of the caller's token a preview leaves unused, for the run's last requests. */
    static final Duration TOKEN_MARGIN = Duration.ofSeconds(30);
    /** The least a preview is given to run; a token with less left is refused. */
    static final Duration MIN_PREVIEW = Duration.ofMinutes(1);

    private final FlowService flowService;
    private final FlowRepository flows;
    private final RunRepository runs;
    private final RunCoordinator coordinator;
    private final FlowCompiler compiler;
    private final TransactionTemplate transaction;
    private final JsonMapper json;
    private final RegisteredClients clients;
    private final FlowTokens offlineTokens;

    public RunService(FlowService flowService, FlowRepository flows, RunRepository runs,
                      RunCoordinator coordinator, FlowCompiler compiler, TransactionTemplate transaction,
                      JsonMapper json, RegisteredClients clients, FlowTokens offlineTokens) {
        this.flowService = flowService;
        this.flows = flows;
        this.runs = runs;
        this.coordinator = coordinator;
        this.compiler = compiler;
        this.transaction = transaction;
        this.json = json;
        this.clients = clients;
        this.offlineTokens = offlineTokens;
    }

    /**
     * What a deployment asks for: a version (default the current one), whether it is on, and
     * optionally a registered client for the runs to act as.
     */
    public record DeployRequest(Integer version, Boolean enabled, String runAs) {
    }

    /** A version made ready to deploy: its trigger and limits, read from its definition. */
    public record DeploymentPlan(long flowId, String flowExternalId, int version, Schedule schedule,
                                 int maxConcurrentRuns, int maxAttempts, Duration timeout) {
    }

    /** What a manual run asks for: a window (default the trigger's latest) and parameter values. */
    public record StartRequest(RunView.Window window, JsonNode parameters) {
    }

    /**
     * What a preview asks for: a saved flow ({@code flow}, and a {@code version}, default its
     * current one) or an unsaved {@code definition}; a window (default the last hour) and parameter
     * values.
     */
    public record PreviewRequest(String flow, Integer version, JsonNode definition,
                                 RunView.Window window, JsonNode parameters) {
    }

    // ---------------------------------------------------------------- deployments

    public DeploymentView deployment(String externalId) {
        StoredFlow flow = flowService.visible(flowService.requireRead(), externalId);
        return flows.deployment(flow.id()).map(d -> DeploymentView.of(d, json)).orElseThrow(() ->
                FlowProblems.notFound("Flow " + externalId + " has not been deployed."));
    }

    /**
     * Changes a deployment: its version, whether it is on, or — with {@code runAs} — the registered
     * client its runs act as, which takes the superset check. Without {@code runAs} the runs keep
     * acting as whoever they act as now: changing the version or switching a flow on or off is not a
     * new authorisation. A flow that has never been deployed has no one to act as yet, and is first
     * deployed through {@code POST /flows/{id}/deployment/authorize} (as the caller) or with
     * {@code runAs}. A run in progress finishes on the version it started with.
     */
    public DeploymentView deploy(String externalId, DeployRequest request) {
        Caller caller = flowService.requireWrite();
        StoredFlow flow = writable(caller, externalId);
        DeploymentPlan plan = plan(flow, request == null ? null : request.version());
        boolean enabled = request == null || request.enabled() == null || request.enabled();
        String tenantId = TenantContext.getTenantId();

        Optional<IdentityState> current = flows.identity(flow.id());
        if (request != null && request.runAs() != null) {
            Identity client = clients.assign(tenantId, request.runAs(), flow.dataSetId());
            DeploymentView view = apply(plan, enabled, client, caller.subject());
            if (current.isPresent() && Identity.DEPLOYER.equals(current.get().identity().kind())) {
                // The runs no longer act as a person: their offline token goes, and is revoked.
                offlineTokens.remove(tenantId, flow.id());
            }
            return view;
        }
        if (current.isEmpty()) {
            throw FlowProblems.conflict("Flow " + externalId + " has no identity for its runs yet. "
                    + "Deploy it as yourself with POST /flows/" + externalId + "/deployment/authorize, "
                    + "or name a registered client with runAs.");
        }
        return apply(plan, enabled, null, caller.subject());
    }

    /**
     * Reads the trigger and limits of version {@code version} of a flow (default its current one),
     * checking that the version still compiles with the processors this service has now.
     */
    public DeploymentPlan plan(StoredFlow flow, Integer version) {
        int v = version == null ? flow.currentVersion() : version;
        String body = flows.version(flow.id(), v).orElseThrow(() -> FlowProblems.notFound(
                "Flow " + flow.externalId() + " has no version " + v + ".")).definition();
        FlowDefinition definition = FlowDefinitions.parse(body);
        // A saved version validated when it was saved; it must still compile with the processors
        // this service has now.
        compiler.compile(definition);

        Schedule schedule;
        try {
            schedule = Schedule.of(definition);
        } catch (IllegalArgumentException e) {
            throw FlowProblems.badRequest(e.getMessage());
        }
        FlowDefinition.Execution execution = definition.execution();
        int maxConcurrent = execution == null || execution.maxConcurrentRuns() == null
                ? DEFAULT_MAX_CONCURRENT_RUNS : execution.maxConcurrentRuns();
        int maxAttempts = execution == null || execution.maxAttempts() == null
                ? DEFAULT_MAX_ATTEMPTS : execution.maxAttempts();
        return new DeploymentPlan(flow.id(), flow.externalId(), v, schedule, maxConcurrent, maxAttempts,
                timeout(definition));
    }

    private static Duration timeout(FlowDefinition definition) {
        FlowDefinition.Execution execution = definition.execution();
        return execution == null || execution.timeout() == null
                ? DEFAULT_TIMEOUT : Duration.parse(execution.timeout());
    }

    /**
     * Writes a deployment: the plan's trigger and limits take effect for the next run created, and,
     * with an {@code identity}, the runs act as it from now on (without one they keep theirs).
     */
    public DeploymentView apply(DeploymentPlan plan, boolean enabled, Identity identity, String by) {
        Schedule schedule = plan.schedule();
        transaction.executeWithoutResult(status -> {
            Instant nextTick = enabled && schedule.scheduled() ? schedule.nextTick(runs.now()) : null;
            flows.deploy(plan.flowId(), plan.version(), enabled, schedule.toJson(json),
                    plan.maxConcurrentRuns(), plan.maxAttempts(), plan.timeout(),
                    identity, by, nextTick, nextTick == null ? null : schedule.dueAt(nextTick));
        });
        log.info("Flow {} deployed at version {} ({}){}", plan.flowExternalId(), plan.version(),
                enabled ? "on" : "off", identity == null ? "" : ", acting as a " + identity.kind());
        return DeploymentView.of(flows.deployment(plan.flowId()).orElseThrow(), json);
    }

    // ---------------------------------------------------------------- runs

    /** Starts a manual run of the deployed version, on this instance at once if it has room. */
    public RunView start(String externalId, StartRequest request) {
        Caller caller = flowService.requireWrite();
        StoredFlow flow = writable(caller, externalId);
        Deployment deployment = runnable(flow);
        Schedule schedule = Schedule.fromJson(json, deployment.trigger());
        TimeWindow window;
        if (request != null && request.window() != null) {
            window = window(request.window());
        } else {
            window = schedule.defaultWindow(runs.now());
            if (window == null) {
                throw FlowProblems.badRequest("Flow " + externalId + " has no window in its trigger, "
                        + "so a run must be given one: {\"window\": {\"start\": …, \"end\": …}}.");
            }
        }
        String parameters = parameters(request == null ? null : request.parameters());
        UUID id = create(flow, deployment, window, parameters, "manual", caller.subject());
        coordinator.claim(TenantContext.getTenantId(), id);
        return get(id, caller);
    }

    /**
     * Starts a preview: a run of a saved version, or of a definition that was never saved, acting as
     * the caller, whose sinks report what they would write instead of writing it. It runs here and
     * now, with the token the caller called with — which exists only on this instance and cannot be
     * refreshed — so nothing else can claim it; its timeout is cut to what the token has left. Its
     * records are kept, so the console follows it like any run; it never raises a failure event and
     * never moves a schedule. The window defaults to the last hour.
     */
    public RunView preview(PreviewRequest request) {
        Caller caller = flowService.requireWrite();
        if (request == null || (request.flow() == null) == (request.definition() == null)) {
            throw FlowProblems.badRequest("A preview takes either a saved flow, {\"flow\": …, "
                    + "\"version\": …}, or an unsaved definition, {\"definition\": {…}}.");
        }
        Long flowId = null;
        Integer version = null;
        String unsaved = null;
        Long dataSetId = null;
        FlowDefinition definition;
        if (request.flow() != null) {
            StoredFlow flow = writable(caller, request.flow());
            int v = request.version() == null ? flow.currentVersion() : request.version();
            definition = FlowDefinitions.parse(flows.version(flow.id(), v).orElseThrow(() ->
                    FlowProblems.notFound("Flow " + flow.externalId() + " has no version " + v + "."))
                    .definition());
            // Saved versions validated when they were saved; they must still compile now.
            compiler.compile(definition);
            flowId = flow.id();
            version = v;
        } else {
            if (!request.definition().isObject()) {
                throw FlowProblems.badRequest("definition is a flow definition object.");
            }
            unsaved = json.writeValueAsString(request.definition());
            definition = FlowDefinitions.parse(unsaved);
            dataSetId = flowService.checkForPreview(caller, definition);
        }

        Jwt jwt = CallerAccess.currentJwt();
        Duration timeout = timeout(definition);
        if (jwt.getExpiresAt() != null) {
            Duration left = Duration.between(Instant.now(), jwt.getExpiresAt()).minus(TOKEN_MARGIN);
            if (left.compareTo(MIN_PREVIEW) < 0) {
                throw FlowProblems.tokenExpiring("Your token expires too soon for a preview to run "
                        + "as you. Get a fresh one and preview again.");
            }
            if (left.compareTo(timeout) < 0) {
                timeout = left;
            }
        }
        TimeWindow window;
        if (request.window() != null) {
            window = window(request.window());
        } else {
            Instant now = runs.now();
            window = new TimeWindow(now.minus(PREVIEW_WINDOW), now);
        }
        RunRepository.NewPreview preview = new RunRepository.NewPreview(flowId, version, unsaved,
                dataSetId, timeout, window, parameters(request.parameters()),
                caller.subject());
        UUID id = transaction.execute(status -> runs.insertPreview(preview));
        String token = jwt.getTokenValue();
        if (!coordinator.claimPreview(TenantContext.getTenantId(), id, () -> token)) {
            runs.cancelPending(id, RunCoordinator.NO_CAPACITY, "The instance that received the "
                    + "preview had no room to run it.");
            throw FlowProblems.busy("This instance is running as much as it may. Preview again "
                    + "shortly.");
        }
        return get(id, caller);
    }

    /** A new run over the same window and with the same values, of the currently deployed version. */
    public RunView rerun(UUID runId) {
        Caller caller = flowService.requireWrite();
        StoredRun original = visibleRun(caller, runId);
        if (original.preview()) {
            throw FlowProblems.badRequest("A preview is not re-run; preview again instead.");
        }
        StoredFlow flow = writable(caller, original.flowExternalId());
        Deployment deployment = runnable(flow);
        UUID id = create(flow, deployment, original.window(), original.parameters(), "rerun",
                caller.subject());
        coordinator.claim(TenantContext.getTenantId(), id);
        return get(id, caller);
    }

    public RunView get(UUID runId) {
        return RunView.of(visibleRun(flowService.requireRead(), runId), json);
    }

    /**
     * The run, for a caller who may see its flow; otherwise a 404. What reading anything of a run —
     * its records, its live view — takes.
     */
    public StoredRun readable(UUID runId) {
        return visibleRun(flowService.requireRead(), runId);
    }

    /** Runs the caller may see, newest first, optionally of one flow, in pages; previews only on request. */
    public DataWrapper<RunView> list(String flowExternalId, boolean previews, String cursor,
                                     Integer limit) {
        Caller caller = flowService.requireRead();
        int pageSize = limit == null || limit <= 0 ? DEFAULT_LIMIT : limit;
        if (pageSize > MAX_LIMIT) {
            throw FlowProblems.badRequest("limit is at most " + MAX_LIMIT + ".");
        }
        PageCursor after = PageCursor.decode(cursor);
        if (after != null && !after.matches(CURSOR_PROPERTY, true)) {
            throw FlowProblems.badRequest("The cursor belongs to another listing.");
        }
        Long flowId = flowExternalId == null ? null : flowService.visible(caller, flowExternalId).id();
        Caller.DataSets readable = caller.admin() ? Caller.DataSets.ALL : caller.read();
        List<StoredRun> page = runs.list(readable, flowId, previews,
                after == null ? null : Instant.parse(after.value()),
                after == null ? null : UUID.fromString(after.id()), pageSize);
        DataWrapper<RunView> out = new DataWrapper<>();
        out.setItems(page.stream().map(r -> RunView.of(r, json)).toList());
        if (page.size() == pageSize) {
            StoredRun last = page.getLast();
            out.setNextCursor(new PageCursor(CURSOR_PROPERTY, true, last.createdAt().toString(),
                    last.id().toString()).encode());
        }
        return out;
    }

    /** A pending run is cancelled at once; a running one is asked to stop at its next heartbeat. */
    public RunView cancel(UUID runId) {
        Caller caller = flowService.requireWrite();
        StoredRun run = visibleRun(caller, runId);
        if (run.dataSetId() != null && !caller.canWrite(run.dataSetId())) {
            throw FlowProblems.datasetForbidden("Cancelling runs of this flow takes write access to "
                    + "its dataset.");
        }
        if (!runs.cancelIfPending(runId, caller.subject()) && !runs.requestCancel(runId)) {
            throw FlowProblems.conflict("Run " + runId + " has already ended (" + run.status() + ").");
        }
        return get(runId, caller);
    }

    // ---------------------------------------------------------------- helpers

    private UUID create(StoredFlow flow, Deployment deployment, TimeWindow window, String parameters,
                        String trigger, String by) {
        return transaction.execute(status -> runs.insert(new NewRun(flow.id(), deployment.version(),
                deployment.maxAttempts(), deployment.timeout(), window,
                parameters, trigger, by, deployment.identitySubject() != null
                        ? deployment.identitySubject() : deployment.identityClientName())));
    }

    private RunView get(UUID runId, Caller caller) {
        return RunView.of(visibleRun(caller, runId), json);
    }

    private StoredFlow writable(Caller caller, String externalId) {
        StoredFlow flow = flowService.visible(caller, externalId);
        if (!caller.canWrite(flow.dataSetId())) {
            throw FlowProblems.datasetForbidden("This takes write access to dataset "
                    + flow.dataSetExternalId() + ".");
        }
        return flow;
    }

    private Deployment runnable(StoredFlow flow) {
        Deployment deployment = flows.deployment(flow.id()).orElseThrow(() ->
                FlowProblems.conflict("Flow " + flow.externalId() + " has not been deployed."));
        if (!deployment.enabled()) {
            throw FlowProblems.conflict("Flow " + flow.externalId() + " is switched off"
                    + (deployment.disabledReason() == null ? "." : " (" + deployment.disabledReason() + ")."));
        }
        return deployment;
    }

    /** The run, if the caller may see its flow; otherwise a 404, as if it did not exist. */
    private StoredRun visibleRun(Caller caller, UUID runId) {
        return runs.find(runId)
                .filter(r -> r.dataSetId() != null && caller.canRead(r.dataSetId()))
                .orElseThrow(() -> FlowProblems.notFound("No run " + runId + "."));
    }

    private static TimeWindow window(RunView.Window w) {
        if (w.start() == null || w.end() == null || !w.start().isBefore(w.end())) {
            throw FlowProblems.badRequest("A window needs a start before its end.");
        }
        return new TimeWindow(w.start(), w.end());
    }

    /** The parameter values as given, checked to be what a parameter can hold; null for none. */
    private String parameters(JsonNode parameters) {
        if (parameters == null || parameters.isNull()) {
            return null;
        }
        if (!parameters.isObject()) {
            throw FlowProblems.badRequest("parameters is an object of values by name.");
        }
        parameters.properties().forEach(e -> {
            try {
                ConfiguredValue.fromJson(e.getValue());
            } catch (IllegalArgumentException ex) {
                throw FlowProblems.badRequest("Parameter " + e.getKey() + ": " + ex.getMessage());
            }
        });
        return json.writeValueAsString(parameters);
    }
}
