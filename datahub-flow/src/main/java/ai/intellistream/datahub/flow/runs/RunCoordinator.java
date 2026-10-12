// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.flow.runs;

import ai.intellistream.datahub.flow.engine.RunResult;
import ai.intellistream.datahub.flow.flows.FlowRepository;
import ai.intellistream.datahub.flow.engine.RunResult.LogLine;
import ai.intellistream.datahub.flow.runs.RunRepository.Candidate;
import ai.intellistream.datahub.flow.runs.RunRepository.Claimed;
import ai.intellistream.datahub.flow.runs.RunRepository.DueFlow;
import ai.intellistream.datahub.flow.runs.RunRepository.Expired;
import ai.intellistream.datahub.flow.runs.RunRepository.FlowGate;
import ai.intellistream.datahub.flow.runs.RunRepository.NewRun;
import ai.intellistream.datahub.flow.runs.RunRepository.Outcome;
import ai.intellistream.datahub.flow.runs.RunRepository.Renewed;
import ai.intellistream.datahub.flow.tenant.FlowSchemaCheck;
import ai.intellistream.datahub.tenant.Tenant;
import ai.intellistream.datahub.tenant.TenantConfigService;
import ai.intellistream.datahub.tenant.TenantContext;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.apache.arrow.memory.BufferAllocator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * How the instances share the work (the plan's <em>How many instances share the work</em> and
 * Appendix B). Every instance runs the same three loops over every tenant with flows, and all
 * coordination is rows in that tenant's Postgres:
 * <ol>
 *   <li><b>Sweep</b> (~10 s): creates a pending run for every schedule tick that is due — oldest
 *       first, at most {@code catch-up} per flow per sweep — and moves the schedule on <em>when the
 *       run is created</em>, never when it finishes; then claims pending runs, earliest window first,
 *       as far as this instance has free run slots and free Arrow memory.</li>
 *   <li><b>Heartbeat</b> (~15 s): renews the leases of all of this instance's runs in a tenant with
 *       one statement. A run missing from the answer was taken over and is stopped and fenced.</li>
 *   <li><b>Reaper</b> (~30 s): runs whose lease expired go back to pending for another attempt, or
 *       fail when their attempts are used up.</li>
 * </ol>
 * The schedule and the claim are two transactions, not one: a sweep locks flow rows for its
 * schedules and the claim locks flow rows in id order, and doing both in one transaction would let
 * two instances lock the same flows in different orders.
 *
 * <p>One tenant's failure never stops another's: each tenant is its own try, and an error is
 * logged and left for the next round.
 */
@Slf4j
@Component
public class RunCoordinator {

    /** The run's identity could not be obtained right now (Keycloak unreachable): retried. */
    public static final String AUTH = "AUTH";
    /**
     * Keycloak refused the run's identity, or the deployment has none: the deployment needs
     * re-authorisation, and no attempt will get past it, so the run is not retried.
     */
    public static final String IDENTITY_REJECTED = "IDENTITY_REJECTED";
    /** The flow's dataset was deleted: the flow is switched off and the run ends, not retried. */
    public static final String DATASET_DELETED = FlowRepository.DATASET_DELETED;
    /** The run went past its timeout. */
    public static final String TIMEOUT = "TIMEOUT";
    /** The flow was switched off before the run could start. */
    public static final String FLOW_DISABLED = "FLOW_DISABLED";
    /** Something in the service itself failed the run. */
    public static final String INTERNAL = "INTERNAL";
    /**
     * Pulsar did not acknowledge the run's records in time: they are not durable, so the run is not
     * finished as succeeded. Retried like any failure.
     */
    public static final String RECORDS_UNPUBLISHED = "RECORDS_UNPUBLISHED";

    private static final int SCHEDULE_BATCH = 50;
    private static final int REAP_BATCH = 100;
    private static final int MAX_LOG_LINES = 200;

    private final RunRepository runs;
    private final RunExecution execution;
    private final RunEvents events;
    private final TransactionTemplate transaction;
    private final TenantConfigService tenants;
    private final FlowSchemaCheck schemaCheck;
    private final JsonMapper json;
    private final Settings settings;
    private final long memoryBudget;
    private final String owner;
    private final LongSupplier nanoTime;

    private final Map<UUID, ActiveRun> active = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private volatile boolean stopping;

    /**
     * @param lease        how long a lease lasts from each renewal
     * @param maxRunning   runs this instance executes at once
     * @param claimsPerSweep runs claimed at most per sweep, so the first instance to sweep does not
     *                     take everything
     * @param catchUp      ticks created at most per flow per sweep after downtime
     * @param backoff      the wait before an attempt's retry, doubling per attempt up to {@code maxBackoff}
     * @param overdueGrace how long past its timeout a run may go before the reaper asks it to stop
     */
    public record Settings(Duration lease, Duration heartbeat, int maxRunning, int claimsPerSweep,
                           int catchUp, Duration backoff, Duration maxBackoff, Duration overdueGrace) {
    }

    @Autowired
    public RunCoordinator(RunRepository runs, RunExecution execution, RunEvents events,
                          TransactionTemplate transaction, TenantConfigService tenants, FlowSchemaCheck schemaCheck, JsonMapper json,
                          BufferAllocator rootAllocator,
                          @Value("${datahub.flow.runs.lease:PT60S}") Duration lease,
                          @Value("${datahub.flow.runs.heartbeat-interval:PT15S}") Duration heartbeat,
                          @Value("${datahub.flow.runs.max-running:8}") int maxRunning,
                          @Value("${datahub.flow.runs.claims-per-sweep:4}") int claimsPerSweep,
                          @Value("${datahub.flow.runs.catch-up:24}") int catchUp,
                          @Value("${datahub.flow.runs.backoff:PT30S}") Duration backoff,
                          @Value("${datahub.flow.runs.max-backoff:PT15M}") Duration maxBackoff,
                          @Value("${datahub.flow.runs.overdue-grace:PT1M}") Duration overdueGrace) {
        this(runs, execution, events, transaction, tenants, schemaCheck, json, rootAllocator.getLimit(),
                new Settings(lease, heartbeat, maxRunning, claimsPerSweep, catchUp, backoff,
                        maxBackoff, overdueGrace),
                instanceId(), System::nanoTime);
    }

    RunCoordinator(RunRepository runs, RunExecution execution, RunEvents events,
                   TransactionTemplate transaction, TenantConfigService tenants, FlowSchemaCheck schemaCheck, JsonMapper json,
                   long memoryBudget, Settings settings, String owner, LongSupplier nanoTime) {
        // Between renewals a lease has at least lease - heartbeat left, and the fence lets a request
        // start only if it can finish inside what is left. So a request must fit in that, or the
        // fence would refuse requests on runs whose lease is perfectly healthy.
        if (execution.requestTimeout().plus(settings.heartbeat()).compareTo(settings.lease()) >= 0) {
            throw new IllegalStateException("datahub.flow.runs.request-timeout ("
                    + execution.requestTimeout() + ") plus the heartbeat interval ("
                    + settings.heartbeat() + ") must be shorter than the lease (" + settings.lease()
                    + "), or the lease fence refuses DataHub requests of runs that hold their lease.");
        }
        this.runs = runs;
        this.execution = execution;
        this.events = events;
        this.transaction = transaction;
        this.tenants = tenants;
        this.schemaCheck = schemaCheck;
        this.json = json;
        this.memoryBudget = memoryBudget;
        this.settings = settings;
        this.owner = owner;
        this.nanoTime = nanoTime;
    }

    /** This instance's name as an owner of runs: the host and a random suffix, unique per start. */
    private static String instanceId() {
        String host;
        try {
            host = InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            host = "flow";
        }
        return host + "-" + Long.toHexString(ThreadLocalRandom.current().nextLong() & 0xffffffL);
    }

    String owner() {
        return owner;
    }

    // ---------------------------------------------------------------- the loops

    @Scheduled(initialDelayString = "${datahub.flow.runs.sweep-interval:PT10S}",
            fixedDelayString = "${datahub.flow.runs.sweep-interval:PT10S}")
    public void sweepAll() {
        forEachFlowTenant("sweep", this::sweep);
    }

    @Scheduled(initialDelayString = "${datahub.flow.runs.heartbeat-interval:PT15S}",
            fixedDelayString = "${datahub.flow.runs.heartbeat-interval:PT15S}")
    public void heartbeatAll() {
        Set<String> withRuns = new HashSet<>();
        active.values().forEach(run -> withRuns.add(run.tenantId));
        for (String tenantId : withRuns) {
            try {
                TenantContext.runWith(tenantId, () -> heartbeat(tenantId));
            } catch (RuntimeException e) {
                log.warn("Heartbeat for tenant {} failed; its runs stop if their leases run out: {}",
                        tenantId, e.getMessage());
            }
        }
        // Self-fencing: a lease that could not be renewed in time is treated as lost.
        active.values().forEach(ActiveRun::shouldStop);
    }

    @Scheduled(initialDelayString = "${datahub.flow.runs.reaper-interval:PT30S}",
            fixedDelayString = "${datahub.flow.runs.reaper-interval:PT30S}")
    public void reapAll() {
        forEachFlowTenant("reaper", this::reap);
    }

    private void forEachFlowTenant(String loop, java.util.function.Consumer<String> work) {
        Map<String, Tenant> registry = tenants.cachedTenants;
        if (registry == null || stopping) {
            return;
        }
        for (Tenant tenant : registry.values()) {
            if (tenant.getFeatures() == null || !tenant.getFeatures().isFlowFeatureEnabled()) {
                continue;
            }
            String tenantId = tenant.getOrganizationId();
            try {
                TenantContext.runWith(tenantId, () -> {
                    if (schemaCheck.isProvisioned(tenantId)) {
                        work.accept(tenantId);
                    }
                });
            } catch (RuntimeException e) {
                log.warn("The {} failed for tenant {}; trying again next round: {}", loop, tenantId,
                        e.getMessage(), e);
            }
        }
    }

    // ---------------------------------------------------------------- sweep and claim

    /**
     * One sweep of the current tenant: due schedules into pending runs, then claims, then the
     * lifecycle events that earlier runs could not write when they ended.
     */
    void sweep(String tenantId) {
        createScheduledRuns();
        claim(tenantId, null);
        events.payOwed(tenantId);
    }

    /** Creates the runs of due schedule ticks; returns how many. */
    int createScheduledRuns() {
        Integer created = transaction.execute(status -> {
            Instant now = runs.now();
            int count = 0;
            for (DueFlow flow : runs.lockDueSchedules(SCHEDULE_BATCH)) {
                Schedule schedule = Schedule.fromJson(json, flow.trigger());
                Schedule.Due due = schedule.due(flow.nextTick(), now, settings.catchUp());
                for (Instant tick : due.ticks()) {
                    runs.insert(new NewRun(flow.id(), flow.version(), flow.maxAttempts(),
                            flow.memoryBytes(), flow.timeout(), schedule.windowOf(tick), null,
                            "schedule", null, flow.runAs()));
                    count++;
                }
                Instant last = due.ticks().isEmpty() ? null : due.ticks().getLast();
                runs.advanceSchedule(flow.id(), due.next(),
                        due.next() == null ? null : schedule.dueAt(due.next()), last);
            }
            return count;
        });
        return created == null ? 0 : created;
    }

    /**
     * Claims pending runs of the current tenant this instance has room for — or only {@code only},
     * for a manual run started where it was requested — and starts them.
     *
     * @return the runs started
     */
    synchronized List<UUID> claim(String tenantId, UUID only) {
        if (stopping) {
            return List.of();
        }
        int slots = settings.maxRunning() - active.size();
        if (slots <= 0) {
            return List.of();
        }
        long reserved = active.values().stream().mapToLong(r -> r.claimed.memoryBytes()).sum();
        // Before the transaction: the database's now() is its start, so a lease is measured from no
        // later than this.
        long sentAt = nanoTime.getAsLong();
        List<ActiveRun> started = transaction.execute(status -> {
            List<ActiveRun> out = new ArrayList<>();
            List<Candidate> candidates = runs.lockPending(Math.min(slots, settings.claimsPerSweep()), only);
            Set<Long> flowIds = new HashSet<>();
            candidates.forEach(c -> {
                if (c.flowId() != null) {
                    flowIds.add(c.flowId());
                }
            });
            Map<Long, FlowGate> gates = runs.lockFlows(flowIds);
            Map<Long, Integer> running = new HashMap<>();
            gates.forEach((id, g) -> running.put(id, g.running()));
            long free = memoryBudget - reserved;
            for (Candidate c : candidates) {
                if (c.memoryBytes() > free) {
                    continue;
                }
                if (c.flowId() != null) {
                    FlowGate gate = gates.get(c.flowId());
                    if (gate == null || !gate.enabled() || gate.deployedVersion() == null) {
                        runs.cancelPending(c.id(), FLOW_DISABLED, "The flow was switched off before "
                                + "the run could start.");
                        continue;
                    }
                    if (running.get(c.flowId()) >= gate.maxConcurrentRuns()) {
                        continue;
                    }
                    running.merge(c.flowId(), 1, Integer::sum);
                }
                // Whether the flow's dataset still exists is checked when the run starts, as the
                // run's identity (RunExecution): a call to datahub-api has no place inside this
                // transaction, which holds the flow rows locked.
                Claimed claimed = runs.claim(c.id(), owner, settings.lease()).orElse(null);
                if (claimed != null) {
                    out.add(new ActiveRun(tenantId, claimed, sentAt, nanoTime));
                    free -= c.memoryBytes();
                }
            }
            return out;
        });
        List<UUID> ids = new ArrayList<>();
        for (ActiveRun run : started == null ? List.<ActiveRun>of() : started) {
            start(run);
            ids.add(run.id());
        }
        return ids;
    }

    private void start(ActiveRun run) {
        active.put(run.id(), run);
        log.info("Run {} attempt {} started", run.id(), run.claimed.attempt());
        executor.execute(() -> {
            try {
                TenantContext.runWith(run.tenantId, () -> finish(run, executeSafely(run)));
            } catch (RuntimeException e) {
                log.error("Run {} could not be finished; its lease will expire and the reaper "
                        + "takes it from there", run.id(), e);
            } finally {
                active.remove(run.id());
            }
        });
    }

    private RunResult executeSafely(ActiveRun run) {
        try {
            return execution.execute(run);
        } catch (RuntimeException | Error e) {
            log.error("Run {} failed inside the service", run.id(), e);
            String error = "The flow service failed the run: " + e;
            return new RunResult(RunResult.Status.FAILED, INTERNAL, error, null, Map.of(), Set.of(),
                    Map.of(), List.of(new LogLine(Instant.now(), null, "ERROR", error)), 0);
        }
    }

    // ---------------------------------------------------------------- finishing

    /** Writes how an attempt ended — only while this instance still owns it. */
    void finish(ActiveRun run, RunResult result) {
        Claimed c = run.claimed;
        ActiveRun.Stop stop = run.stopReason();
        boolean owned;
        boolean eventOwed = false;
        if (stop == ActiveRun.Stop.LEASE_LOST) {
            log.warn("Run {} attempt {} lost its lease; another instance or the reaper takes it on",
                    c.id(), c.attempt());
            return;
        }
        // A run told to stop did not do all of its work, even when its processors returned normally
        // on being told: a stop reason outranks the engine's SUCCEEDED, except at shutdown, where a
        // run that did finish is recorded as finished.
        if (stop == ActiveRun.Stop.SHUTDOWN && result.status() != RunResult.Status.SUCCEEDED) {
            owned = runs.release(c.id(), owner, c.attempt());
            log.info("Run {} handed back as this instance shuts down", c.id());
        } else if (stop == ActiveRun.Stop.CANCELLED) {
            owned = runs.complete(c.id(), owner, c.attempt(), "CANCELLED", false,
                    outcome(result, RunResult.CANCELLED));
        } else if (stop != ActiveRun.Stop.TIMEOUT && result.status() == RunResult.Status.SUCCEEDED) {
            // A success for a window that failed before owes the check that resolves its lifecycle.
            eventOwed = !c.preview() && c.flowId() != null && c.window() != null
                    && runs.failedBefore(c.flowId(), c.window().start());
            owned = runs.complete(c.id(), owner, c.attempt(), "SUCCEEDED", eventOwed,
                    outcome(result, null));
        } else if (DATASET_DELETED.equals(result.errorCode())) {
            // Not a failure of the flow: there is nothing left for it to run for. The flow was
            // switched off; no event, since the dataset an event would go to is gone.
            owned = runs.complete(c.id(), owner, c.attempt(), "CANCELLED", false, outcome(result, null));
        } else {
            Outcome outcome = outcome(result, stop == ActiveRun.Stop.TIMEOUT ? TIMEOUT : null);
            if (c.attempt() < c.maxAttempts() && !IDENTITY_REJECTED.equals(result.errorCode())) {
                owned = runs.retry(c.id(), owner, c.attempt(), backoff(c.attempt()), outcome);
            } else {
                eventOwed = !c.preview();
                owned = runs.complete(c.id(), owner, c.attempt(), "FAILED", eventOwed, outcome);
            }
        }
        if (!owned) {
            log.warn("Run {} attempt {} was no longer this instance's when it finished; its result "
                    + "is not recorded", c.id(), c.attempt());
        } else {
            log.info("Run {} attempt {} ended: {} {}", c.id(), c.attempt(), result.status(),
                    result.errorCode() == null ? "" : result.errorCode());
            if (eventOwed) {
                // Right away, so Needs attention shows it now; a sweep pays it if this cannot.
                runs.find(c.id()).ifPresent(stored -> events.pay(run.tenantId, stored));
            }
        }
    }

    /** The wait before attempt {@code attempt + 1}: doubling from the base, capped. */
    Duration backoff(int attempt) {
        Duration wait = settings.backoff().multipliedBy(1L << Math.min(attempt - 1, 20));
        return wait.compareTo(settings.maxBackoff()) > 0 ? settings.maxBackoff() : wait;
    }

    private Outcome outcome(RunResult result, String code) {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("records", result.records());
        if (result.failedProcessor() != null) {
            stats.put("failedProcessor", result.failedProcessor());
        }
        List<Map<String, Object>> lines = new ArrayList<>();
        for (LogLine line : result.log()) {
            if (lines.size() == MAX_LOG_LINES) {
                stats.put("logTruncated", true);
                break;
            }
            Map<String, Object> l = new LinkedHashMap<>();
            l.put("time", line.time().toString());
            if (line.processorId() != null) {
                l.put("processor", line.processorId());
            }
            l.put("level", line.level());
            l.put("message", line.message());
            lines.add(l);
        }
        stats.put("log", lines);
        return new Outcome(code != null ? code : result.errorCode(),
                code == null ? result.error() : timeoutOr(code, result.error()),
                result.parameters().isEmpty() ? null : json.writeValueAsString(result.parameters()),
                result.processorVersions().isEmpty() ? null
                        : json.writeValueAsString(result.processorVersions()),
                json.writeValueAsString(stats));
    }

    private static String timeoutOr(String code, String error) {
        if (TIMEOUT.equals(code)) {
            return "The run went past its timeout.";
        }
        return error != null ? error : "The run was cancelled.";
    }

    // ---------------------------------------------------------------- heartbeat and reaper

    /** Renews the leases of this instance's runs in the current tenant. */
    void heartbeat(String tenantId) {
        Map<UUID, ActiveRun> mine = new HashMap<>();
        active.values().forEach(run -> {
            if (run.tenantId.equals(tenantId)) {
                mine.put(run.id(), run);
            }
        });
        if (mine.isEmpty()) {
            return;
        }
        long sentAt = nanoTime.getAsLong();
        List<Renewed> renewed = runs.renew(mine.keySet(), owner, settings.lease());
        Set<UUID> kept = new HashSet<>();
        for (Renewed r : renewed) {
            ActiveRun run = mine.get(r.id());
            kept.add(r.id());
            run.renewed(sentAt, r.leaseLeft());
            if (r.cancelRequested()) {
                // A run past its timeout that the reaper asked to stop still stops as a timeout.
                if (!run.shouldStop()) {
                    run.stop(ActiveRun.Stop.CANCELLED);
                }
            }
        }
        mine.forEach((id, run) -> {
            if (!kept.contains(id)) {
                log.warn("Run {} is no longer this instance's: stopping it", id);
                run.stop(ActiveRun.Stop.LEASE_LOST);
            }
        });
    }

    /** One reaper pass over the current tenant. */
    void reap(String tenantId) {
        transaction.executeWithoutResult(status -> {
            for (Expired e : runs.lockExpired(REAP_BATCH)) {
                if (e.attempt() < e.maxAttempts()) {
                    runs.requeueExpired(e.id(), e.attempt(), backoff(e.attempt()),
                            "The instance running attempt " + e.attempt() + " stopped renewing its lease.");
                    log.info("Run {} attempt {} lost its instance; queued again", e.id(), e.attempt());
                } else {
                    runs.failExpired(e.id(), e.attempt(), e.preview(),
                            "The instance running the last attempt stopped renewing its lease.");
                    log.info("Run {} lost its instance on its last attempt; failed", e.id());
                }
            }
        });
        runs.cancelOverdue(settings.overdueGrace());
    }

    // ---------------------------------------------------------------- shutdown

    /** Stops claiming, hands every run back as pending, and waits a little for them to do so. */
    @PreDestroy
    public void shutdown() {
        stopping = true;
        active.values().forEach(run -> run.stop(ActiveRun.Stop.SHUTDOWN));
        executor.shutdown();
        try {
            if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
                log.warn("{} runs did not stop in time; their leases will expire", active.size());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** How many runs this instance is executing. */
    int running() {
        return active.size();
    }
}
