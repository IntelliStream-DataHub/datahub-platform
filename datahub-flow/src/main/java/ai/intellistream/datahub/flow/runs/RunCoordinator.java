// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.flow.runs;

import ai.intellistream.datahub.flow.engine.RunResult;
import ai.intellistream.datahub.flow.flows.FlowRepository;
import ai.intellistream.datahub.flow.engine.RunResult.LogLine;
import ai.intellistream.datahub.flow.flows.FlowRepository.DeployedFlow;
import ai.intellistream.datahub.flow.flows.FlowRepository.DueFlow;
import ai.intellistream.datahub.flow.store.RunStore;
import ai.intellistream.datahub.flow.store.RunStore.Claim;
import ai.intellistream.datahub.flow.store.RunStore.Claimed;
import ai.intellistream.datahub.flow.store.RunStore.FlowLimits;
import ai.intellistream.datahub.flow.store.RunStore.NewRun;
import ai.intellistream.datahub.flow.store.RunStore.Outcome;
import ai.intellistream.datahub.flow.store.RunStore.Reaped;
import ai.intellistream.datahub.flow.store.RunStore.Renewed;
import ai.intellistream.datahub.flow.tenant.FlowSchemaCheck;
import ai.intellistream.datahub.tenant.Tenant;
import ai.intellistream.datahub.tenant.TenantConfigService;
import ai.intellistream.datahub.tenant.TenantContext;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
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
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * How the instances share the work (the plan's <em>How many instances share the work</em> and
 * Appendix B). Every instance runs the same loops over every tenant with flows, and all
 * coordination is rows in that tenant's Postgres:
 * <ol>
 *   <li><b>Sweep</b> (~10 s): creates a pending run for every schedule tick that is due — oldest
 *       first, at most {@code catch-up} per flow per sweep — and moves the schedule on <em>when the
 *       run is created</em>, never when it finishes. It also marks every tenant as possibly having
 *       runs waiting, which is how an instance learns of runs queued by another.</li>
 *   <li><b>Dispatch</b> (continuous): fills this instance's free run slots with pending runs. It
 *       runs whenever a run finishes, a run is queued here, or the sweep marks a tenant, so a freed
 *       slot is refilled at once and a queue of thousands drains as fast as the slots allow, not at
 *       the pace of the sweep. Within a tenant the earliest window goes first, each flow offering
 *       only what its {@code maxConcurrentRuns} leaves room for so that a backlog of one flow does
 *       not hold up another; every tenant with runs waiting takes an equal share of what is left at
 *       its turn, and a different tenant goes first each round.</li>
 *   <li><b>Heartbeat</b> (~15 s): renews the leases of all of this instance's runs in a tenant with
 *       one statement. A run missing from the answer was taken over and is stopped and fenced.</li>
 *   <li><b>Reaper</b> (~30 s): runs whose lease expired go back to pending for another attempt, or
 *       fail when their attempts are used up.</li>
 * </ol>
 * The schedule lives in Postgres and the runs in the tenant's KVRocks (RUN_STORE.md), so a tick
 * becoming a run is two writes in two stores. The run is enqueued first, with an id derived from the
 * flow and the tick, and the schedule is advanced second: a crash between them repeats the tick at
 * the next sweep, which enqueues the same id and so does nothing.
 *
 * <p>Dispatch is one thread per instance, woken by {@link #poke}. A tenant is <em>hungry</em> from
 * the moment something may be waiting for it, and stays so while a dispatch round still starts
 * runs for it; a round that starts none drops it until the next poke, which a finishing run of that
 * tenant gives. A run waiting out its retry back-off is found by the next sweep.
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
    /** The instance running a preview shut down; a preview is not handed back, since no one else can run it. */
    public static final String SHUTDOWN = "SHUTDOWN";
    /** This instance had no room for a preview, which no other instance can take. */
    public static final String NO_CAPACITY = "NO_CAPACITY";
    /** Something in the service itself failed the run. */
    public static final String INTERNAL = "INTERNAL";
    /**
     * Pulsar did not acknowledge the run's records in time: they are not durable, so the run is not
     * finished as succeeded. Retried like any failure.
     */
    public static final String RECORDS_UNPUBLISHED = "RECORDS_UNPUBLISHED";

    private static final int SCHEDULE_BATCH = 50;
    private static final int MAX_SCHEDULE_ROUNDS = 20;
    private static final int REAP_BATCH = 100;
    private static final int MAX_LOG_LINES = 200;

    private final RunStore store;
    private final FlowRepository flows;
    private final RunExecution execution;
    private final RunEvents events;
    private final TransactionTemplate transaction;
    private final TenantConfigService tenants;
    private final FlowSchemaCheck schemaCheck;
    private final JsonMapper json;
    private final Settings settings;
    private final String owner;
    private final LongSupplier nanoTime;

    private final Map<UUID, ActiveRun> active = new ConcurrentHashMap<>();
    /** Sweeps so far, which decides the tenant that claims first in the next one. */
    private final AtomicInteger sweeps = new AtomicInteger();
    /** Sweeps so far, which decides when the run store is told everything again. */
    private final AtomicInteger sweepRounds = new AtomicInteger();
    /** Tenants that may have runs waiting, and the signal that wakes the dispatcher. */
    private final Set<String> hungry = ConcurrentHashMap.newKeySet();
    private final Semaphore wake = new Semaphore(0);
    private volatile Thread dispatcher;
    /** What each tenant's run store was last told of its flows' limits. */
    private final Map<String, Map<Long, FlowLimits>> told = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private volatile boolean stopping;

    /**
     * @param lease        how long a lease lasts from each renewal
     * @param maxRunning   runs this instance executes at once: its run slots
     * @param catchUp      ticks created at most per flow per sweep after downtime
     * @param backoff      the wait before an attempt's retry, doubling per attempt up to {@code maxBackoff}
     * @param overdueGrace how long past its timeout a run may go before the reaper asks it to stop
     */
    public record Settings(Duration lease, Duration heartbeat, int maxRunning,
                           int catchUp, Duration backoff, Duration maxBackoff, Duration overdueGrace) {
    }

    @Autowired
    public RunCoordinator(RunStore store, FlowRepository flows, RunExecution execution, RunEvents events,
                          TransactionTemplate transaction, TenantConfigService tenants, FlowSchemaCheck schemaCheck, JsonMapper json,
                          @Value("${datahub.flow.runs.lease:PT60S}") Duration lease,
                          @Value("${datahub.flow.runs.heartbeat-interval:PT15S}") Duration heartbeat,
                          @Value("${datahub.flow.runs.max-running:8}") int maxRunning,
                          @Value("${datahub.flow.runs.catch-up:24}") int catchUp,
                          @Value("${datahub.flow.runs.backoff:PT30S}") Duration backoff,
                          @Value("${datahub.flow.runs.max-backoff:PT15M}") Duration maxBackoff,
                          @Value("${datahub.flow.runs.overdue-grace:PT1M}") Duration overdueGrace) {
        this(store, flows, execution, events, transaction, tenants, schemaCheck, json,
                new Settings(lease, heartbeat, maxRunning, catchUp, backoff, maxBackoff, overdueGrace),
                instanceId(), System::nanoTime);
    }

    RunCoordinator(RunStore store, FlowRepository flows, RunExecution execution, RunEvents events,
                   TransactionTemplate transaction, TenantConfigService tenants, FlowSchemaCheck schemaCheck, JsonMapper json,
                   Settings settings, String owner, LongSupplier nanoTime) {
        // Between renewals a lease has at least lease - heartbeat left, and the fence lets a request
        // start only if it can finish inside what is left. So a request must fit in that, or the
        // fence would refuse requests on runs whose lease is perfectly healthy.
        if (execution.requestTimeout().plus(settings.heartbeat()).compareTo(settings.lease()) >= 0) {
            throw new IllegalStateException("datahub.flow.runs.request-timeout ("
                    + execution.requestTimeout() + ") plus the heartbeat interval ("
                    + settings.heartbeat() + ") must be shorter than the lease (" + settings.lease()
                    + "), or the lease fence refuses DataHub requests of runs that hold their lease.");
        }
        this.store = store;
        this.flows = flows;
        this.execution = execution;
        this.events = events;
        this.transaction = transaction;
        this.tenants = tenants;
        this.schemaCheck = schemaCheck;
        this.json = json;
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
        sweepRounds.incrementAndGet();
        List<String> tenants = flowTenants();
        forEach(tenants, "sweep", this::sweep);
        // Any tenant may have runs another instance queued; a round that finds none drops it again.
        hungry.addAll(tenants);
        dispatchRound();
    }

    /** Starts the dispatcher: one thread that fills free run slots whenever it is poked. */
    @PostConstruct
    void startDispatching() {
        if (dispatcher == null) {
            dispatcher = Thread.ofVirtual().name("flow-dispatcher").start(this::dispatchLoop);
        }
    }

    /**
     * Says that {@code tenantId} may have runs waiting to start: a run was queued, or one of its runs
     * finished and freed a slot. Cheap, and safe from any thread.
     */
    void poke(String tenantId) {
        hungry.add(tenantId);
        wake.release();
    }

    private void dispatchLoop() {
        while (!stopping) {
            try {
                wake.tryAcquire(1, TimeUnit.SECONDS);
                wake.drainPermits();
                dispatchRound();
            } catch (InterruptedException e) {
                return;
            } catch (RuntimeException e) {
                log.error("Dispatching runs failed; trying again at the next poke or sweep", e);
            }
        }
    }

    /**
     * Fills the free slots from the hungry tenants, round after round while rounds still start runs.
     * A tenant is taken off the hungry set before it is tried and put back only if it started
     * something, so a poke that arrives meanwhile is never lost. Each round either fills a slot or
     * ends the loop.
     */
    void dispatchRound() {
        while (!stopping && settings.maxRunning() - active.size() > 0) {
            List<String> tenants = new ArrayList<>();
            for (String tenantId : List.copyOf(hungry)) {
                if (hungry.remove(tenantId)) {
                    tenants.add(tenantId);
                }
            }
            if (tenants.isEmpty()) {
                return;
            }
            Set<String> started = claimAcross(tenants);
            hungry.addAll(started);
            if (started.isEmpty()) {
                return;
            }
        }
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
        forEach(flowTenants(), "reaper", this::reap);
    }

    /** The tenants the loops work for: flagged for flows, with the flow schema in place. */
    private List<String> flowTenants() {
        List<String> out = new ArrayList<>();
        Map<String, Tenant> registry = tenants.cachedTenants;
        if (registry == null || stopping) {
            return out;
        }
        for (Tenant tenant : registry.values()) {
            if (tenant.getFeatures() == null || !tenant.getFeatures().isFlowFeatureEnabled()) {
                continue;
            }
            String tenantId = tenant.getOrganizationId();
            try {
                if (TenantContext.callWith(tenantId, () -> schemaCheck.isProvisioned(tenantId))) {
                    out.add(tenantId);
                }
            } catch (RuntimeException e) {
                log.warn("Could not reach tenant {}; left out of this round: {}", tenantId, e.getMessage());
            }
        }
        return out;
    }

    /** Runs {@code work} under each tenant in turn; one tenant's failure is logged and left for the next round. */
    private void forEach(List<String> tenantIds, String loop, java.util.function.Consumer<String> work) {
        for (String tenantId : tenantIds) {
            try {
                TenantContext.runWith(tenantId, () -> work.accept(tenantId));
            } catch (RuntimeException e) {
                log.warn("The {} failed for tenant {}; trying again next round: {}", loop, tenantId,
                        e.getMessage(), e);
            }
        }
    }

    // ---------------------------------------------------------------- sweep and claim

    /**
     * One sweep of the current tenant: due schedules into pending runs, then the lifecycle events
     * that earlier runs could not write when they ended. The claims come after every tenant has
     * been swept ({@link #claimAcross}), so that the slots are shared between them.
     */
    void sweep(String tenantId) {
        syncFlows(tenantId);
        createScheduledRuns();
        events.payOwed(tenantId);
    }

    /** Creates the runs of due schedule ticks; returns how many. */
    int createScheduledRuns() {
        int total = 0;
        // A batch of flows at a time, until a batch is not full: a hundred flows due at the same
        // minute are all made runs now, not fifty now and fifty at the next sweep.
        for (int round = 0; round < MAX_SCHEDULE_ROUNDS; round++) {
            AtomicInteger flows = new AtomicInteger();
            total += createScheduledRuns(flows);
            if (flows.get() < SCHEDULE_BATCH) {
                break;
            }
        }
        return total;
    }

    private int createScheduledRuns(AtomicInteger flowsLocked) {
        Integer created = transaction.execute(status -> {
            Instant now = flows.now();
            int count = 0;
            for (DueFlow flow : flows.lockDueSchedules(SCHEDULE_BATCH)) {
                flowsLocked.incrementAndGet();
                Schedule schedule = Schedule.fromJson(json, flow.trigger());
                Schedule.Due due = schedule.due(flow.nextTick(), now, settings.catchUp());
                List<NewRun> ticks = new ArrayList<>();
                for (Instant tick : due.ticks()) {
                    ticks.add(new NewRun(scheduledRunId(flow.id(), tick), flow.id(), flow.externalId(),
                            flow.dataSetId(), flow.version(), flow.maxAttempts(), flow.timeout(),
                            schedule.windowOf(tick), null, "schedule", null, flow.runAs(), null));
                }
                // The runs first, the schedule second: if the transaction fails after this, the same
                // ticks are enqueued again at the next sweep, and the same ids make that a no-op.
                count += store.enqueueAll(ticks);
                Instant last = due.ticks().isEmpty() ? null : due.ticks().getLast();
                flows.advanceSchedule(flow.id(), due.next(),
                        due.next() == null ? null : schedule.dueAt(due.next()), last);
            }
            return count;
        });
        return created == null ? 0 : created;
    }

    /** The id of the run for a flow's schedule tick: the same however many times the tick is found. */
    static UUID scheduledRunId(long flowId, Instant tick) {
        return UUID.nameUUIDFromBytes(("schedule:" + flowId + ":" + tick.toEpochMilli())
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /**
     * Tells the run store the limits of every deployed flow that changed since it was last told, so
     * that dispatch needs nothing from Postgres. Everything is told again now and then, in case the
     * run store lost it.
     */
    int syncFlows(String tenantId) {
        Map<Long, FlowLimits> known = told.computeIfAbsent(tenantId, id -> new ConcurrentHashMap<>());
        if (sweepRounds.get() % 6 == 0) {
            known.clear();
        }
        int changed = 0;
        for (DeployedFlow f : flows.deployedFlows()) {
            FlowLimits limits = limits(f);
            if (!limits.equals(known.get(f.id()))) {
                store.syncFlow(limits);
                known.put(f.id(), limits);
                changed++;
            }
        }
        return changed;
    }

    /** Tells the run store about one flow now, as a deployment or a switch has just changed it. */
    public void syncFlow(long flowId) {
        flows.deployedFlow(flowId).ifPresent(f -> {
            FlowLimits limits = limits(f);
            store.syncFlow(limits);
            told.computeIfAbsent(TenantContext.getTenantId(), id -> new ConcurrentHashMap<>()).put(flowId, limits);
        });
        poke(TenantContext.getTenantId());
    }

    private static FlowLimits limits(DeployedFlow f) {
        return new FlowLimits(f.id(), f.externalId(), f.dataSetId(), f.enabled(), true, f.maxConcurrentRuns());
    }

    /**
     * Claims pending runs of the current tenant as far as this instance's free slots go — or only
     * {@code only}, for a manual run started where it was requested — and starts them.
     *
     * @return the runs started
     */
    List<UUID> claim(String tenantId, UUID only) {
        List<UUID> started = claim(tenantId, only, null, settings.maxRunning());
        // A run that found no room, or whose flow is at its limit, waits for the next free slot.
        poke(tenantId);
        return started;
    }

    /**
     * Claims {@code only}, a preview created on this instance, and runs it with {@code tokens}, the
     * caller's own. No other instance can: the token exists only here.
     *
     * @return whether it started; when this instance has no room it did not, and nobody else will
     */
    boolean claimPreview(String tenantId, UUID only, Supplier<String> tokens) {
        return !claim(tenantId, only, tokens, 1).isEmpty();
    }

    /**
     * Fills this instance's free run slots from the tenants' pending runs. Each tenant in turn may
     * take an equal share of the slots still free, so a tenant with a long queue takes its share
     * and no more while others are waiting; a different tenant goes first each sweep, so none is
     * always last; and whatever is still free afterwards goes to the tenants that used their whole
     * share, which may have more.
     */
    private Set<String> claimAcross(List<String> tenants) {
        Set<String> started = new HashSet<>();
        if (tenants.isEmpty()) {
            return started;
        }
        int first = Math.floorMod(sweeps.getAndIncrement(), tenants.size());
        List<String> tookAll = new ArrayList<>();
        for (int i = 0; i < tenants.size(); i++) {
            int free = settings.maxRunning() - active.size();
            if (free <= 0) {
                return started;
            }
            int left = tenants.size() - i;
            int share = (free + left - 1) / left;
            String tenantId = tenants.get((first + i) % tenants.size());
            int took = claimFor(tenantId, share);
            if (took > 0) {
                started.add(tenantId);
            }
            if (took == share) {
                tookAll.add(tenantId);
            }
        }
        for (String tenantId : tookAll) {
            int free = settings.maxRunning() - active.size();
            if (free <= 0) {
                return started;
            }
            claimFor(tenantId, free);
        }
        return started;
    }

    /** Claims up to {@code atMost} runs of one tenant; a tenant whose claim fails waits for the next sweep. */
    private int claimFor(String tenantId, int atMost) {
        try {
            return TenantContext.callWith(tenantId, () -> claim(tenantId, null, null, atMost)).size();
        } catch (RuntimeException e) {
            log.warn("Claiming runs of tenant {} failed; trying again next sweep: {}", tenantId, e.getMessage(), e);
            return 0;
        }
    }

    private synchronized List<UUID> claim(String tenantId, UUID only, Supplier<String> tokens, int atMost) {
        if (stopping) {
            return List.of();
        }
        int slots = Math.min(settings.maxRunning() - active.size(), atMost);
        if (slots <= 0) {
            return List.of();
        }
        // Before the call: the lease starts on the server's clock no earlier than this.
        long sentAt = nanoTime.getAsLong();
        List<Claimed> claimed;
        if (only != null) {
            claimed = store.claimOne(only, owner, settings.lease(), settings.overdueGrace()) == Claim.CLAIMED
                    ? store.claimedRun(only, settings.lease()).stream().toList() : List.of();
        } else {
            claimed = store.claim(owner, settings.lease(), slots, settings.overdueGrace());
        }
        List<UUID> ids = new ArrayList<>();
        for (Claimed c : claimed) {
            start(new ActiveRun(tenantId, c, tokens, sentAt, nanoTime));
            ids.add(c.id());
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
                // A slot is free, and this tenant's gate for the flow too: refill at once.
                poke(run.tenantId);
            }
        });
    }

    private RunResult executeSafely(ActiveRun run) {
        try {
            return execution.execute(run);
        } catch (RunCredentials.RunIdentityException e) {
            // The run's token is taken again on each request to DataHub, so Keycloak can refuse it
            // mid-run: the identity's failure, which a retry does not fix when it was refused.
            String code = e.rejected() ? IDENTITY_REJECTED : AUTH;
            return new RunResult(RunResult.Status.FAILED, code, e.getMessage(), null, Map.of(), Set.of(),
                    Map.of(), List.of(new LogLine(Instant.now(), null, "ERROR", e.getMessage())), 0);
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
        if (stop == ActiveRun.Stop.SHUTDOWN && result.status() != RunResult.Status.SUCCEEDED
                && c.preview()) {
            // Handed back, nobody could run it: the caller's token it ran with goes with this
            // instance. The person previewing starts another.
            owned = store.complete(c.id(), owner, c.attempt(), "CANCELLED", false,
                    outcome(result, SHUTDOWN));
        } else if (stop == ActiveRun.Stop.SHUTDOWN && result.status() != RunResult.Status.SUCCEEDED) {
            owned = store.release(c.id(), owner, c.attempt());
            log.info("Run {} handed back as this instance shuts down", c.id());
        } else if (stop == ActiveRun.Stop.CANCELLED) {
            owned = store.complete(c.id(), owner, c.attempt(), "CANCELLED", false,
                    outcome(result, RunResult.CANCELLED));
        } else if (stop != ActiveRun.Stop.TIMEOUT && result.status() == RunResult.Status.SUCCEEDED) {
            // A success for a window that failed before owes the check that resolves its lifecycle.
            eventOwed = !c.preview() && c.flowId() != null && c.window() != null
                    && store.failedWindow(c.flowId(), c.window().start());
            owned = store.complete(c.id(), owner, c.attempt(), "SUCCEEDED", eventOwed,
                    outcome(result, null));
        } else if (DATASET_DELETED.equals(result.errorCode())) {
            // Not a failure of the flow: there is nothing left for it to run for. The flow was
            // switched off; no event, since the dataset an event would go to is gone.
            owned = store.complete(c.id(), owner, c.attempt(), "CANCELLED", false, outcome(result, null));
        } else {
            Outcome outcome = outcome(result, stop == ActiveRun.Stop.TIMEOUT ? TIMEOUT : null);
            if (c.attempt() < c.maxAttempts() && !IDENTITY_REJECTED.equals(result.errorCode())) {
                owned = store.retry(c.id(), owner, c.attempt(), backoff(c.attempt()), outcome);
            } else {
                eventOwed = !c.preview();
                owned = store.complete(c.id(), owner, c.attempt(), "FAILED", eventOwed, outcome);
            }
        }
        if (!owned) {
            log.warn("Run {} attempt {} was no longer this instance's when it finished; its result "
                    + "is not recorded", c.id(), c.attempt());
        } else {
            log.info("Run {} attempt {} ended: {} {}", c.id(), c.attempt(), result.status(),
                    result.errorCode() == null ? "" : result.errorCode());
            if (eventOwed) {
                // Right away, so Failed runs shows it now; a sweep pays it if this cannot.
                store.find(c.id()).ifPresent(stored -> events.pay(run.tenantId, stored));
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
        if (SHUTDOWN.equals(code)) {
            return "The instance running the preview shut down.";
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
        List<Renewed> renewed = store.renew(mine.keySet(), owner, settings.lease());
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
        for (Reaped r : store.reap(REAP_BATCH, settings.backoff(), settings.maxBackoff())) {
            if (r.requeued()) {
                log.info("Run {} attempt {} lost its instance; queued again", r.id(), r.attempt());
            } else {
                log.info("Run {} lost its instance on its last attempt; failed", r.id());
            }
        }
        store.cancelOverdue(REAP_BATCH);
        // Whatever was handed back or requeued wants a slot.
        poke(tenantId);
    }

    // ---------------------------------------------------------------- shutdown

    /** Stops claiming, hands every run back as pending, and waits a little for them to do so. */
    @PreDestroy
    public void shutdown() {
        stopping = true;
        wake.release();
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
