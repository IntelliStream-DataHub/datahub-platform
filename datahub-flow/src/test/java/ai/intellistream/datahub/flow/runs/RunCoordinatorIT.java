// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.flow.runs;

import ai.intellistream.datahub.flow.access.Caller;
import ai.intellistream.datahub.flow.api.ProcessContext;
import ai.intellistream.datahub.flow.api.ProcessException;
import ai.intellistream.datahub.flow.api.ProcessSession;
import ai.intellistream.datahub.flow.api.Processor;
import ai.intellistream.datahub.flow.api.ProcessorDescriptor;
import ai.intellistream.datahub.flow.api.PropertyDescriptor;
import ai.intellistream.datahub.flow.api.PropertyType;
import ai.intellistream.datahub.flow.api.Relationship;
import ai.intellistream.datahub.flow.api.TimeWindow;
import ai.intellistream.datahub.flow.definition.FlowValidator;
import ai.intellistream.datahub.flow.engine.FlowCompiler;
import ai.intellistream.datahub.flow.engine.RunExecutor;
import ai.intellistream.datahub.flow.flows.FlowRepository;
import ai.intellistream.datahub.flow.processors.TimeseriesSink;
import ai.intellistream.datahub.flow.records.FlowRecordBatch;
import ai.intellistream.datahub.flow.records.RunRecords;
import ai.intellistream.datahub.flow.runs.RunCredentials.RunIdentityException;
import ai.intellistream.datahub.flow.store.RunStore;
import ai.intellistream.datahub.flow.store.RunStore.FlowLimits;
import ai.intellistream.datahub.flow.store.RunStore.NewPreview;
import ai.intellistream.datahub.flow.store.RunStore.NewRun;
import ai.intellistream.datahub.flow.store.RunStore.StoredRun;
import ai.intellistream.datahub.flow.store.SharedKvrocks;
import ai.intellistream.datahub.flow.store.StoreInvariants;
import ai.intellistream.datahub.flow.store.TenantKvrocks;
import ai.intellistream.datahub.flow.tenant.FlowSchemaCheck;
import ai.intellistream.datahub.tenant.Tenant;
import ai.intellistream.datahub.tenant.TenantConfigService;
import ai.intellistream.datahub.tenant.TenantContext;
import ai.intellistream.datahub.tenant.TenantFeatures;
import ai.intellistream.datahub.testsupport.SharedPostgres;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The run protocol with two instances of the coordinator in one JVM against one tenant database —
 * the plan's way of proving the coordination model deterministically, without a compose stack. The
 * loops are driven by hand (or by a background heartbeat the test can stop), with a two-second lease
 * so that losing one takes seconds, not minutes.
 */
@Tag("integration")
class RunCoordinatorIT {

    private static final String TENANT = "tenant-it";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Duration LEASE = Duration.ofSeconds(2);
    private static final Duration HEARTBEAT = Duration.ofMillis(300);
    private static final Duration REQUEST_TIMEOUT = Duration.ofMillis(300);
    private static final AtomicInteger DATABASES = new AtomicInteger();

    /** Per key: runs executing now, and the most ever at once. */
    private static final Map<String, AtomicInteger> RUNNING = new ConcurrentHashMap<>();
    private static final Map<String, AtomicInteger> MOST = new ConcurrentHashMap<>();
    /** Keys of Wait processors to let go early: a test releases a run it was holding. */
    private static final Set<String> RELEASED = ConcurrentHashMap.newKeySet();

    private JdbcTemplate jdbc;
    private TransactionTemplate transaction;
    private RunStore runs;
    private TenantKvrocks kvrocks;
    private FlowRepository flows;
    private HttpServer datahub;
    private final List<Long> requestArrivals = new CopyOnWriteArrayList<>();
    /** Every request DataHub received, as "METHOD path", and the Authorization header it carried. */
    private final List<String> requests = new CopyOnWriteArrayList<>();
    private final List<String> bearers = new CopyOnWriteArrayList<>();
    private volatile boolean dataSetExists = true;
    /** How often a coordinator tells the run store every flow again; every sweep unless a test says otherwise. */
    private Duration resync = Duration.ZERO;
    /** Ticks a coordinator makes into runs per flow per round of a pass of the ticker. */
    private int catchUp = 24;
    /** How often a coordinator with a free slot looks for runs another one queued. */
    private Duration dispatchPoll = Duration.ofMillis(100);
    /** The coordinators' clock for resyncs and lease fences: real time, which a test can move on to skip a wait. */
    private final AtomicLong skipped = new AtomicLong();
    private final java.util.function.LongSupplier clock = () -> System.nanoTime() + skipped.get();
    private TenantConfigService registry;
    private FlowSchemaCheck schemaCheck;
    private final List<RunCoordinator> coordinators = new ArrayList<>();
    private final Set<RunCoordinator> heartbeating = ConcurrentHashMap.newKeySet();
    private ScheduledExecutorService heartbeats;
    private final List<FlowRecordBatch> published = new CopyOnWriteArrayList<>();
    private volatile boolean failPublishing;
    /** The events POST /events/create received, and the bodies of POST /events/latest. */
    private final List<JsonNode> createdEvents = new CopyOnWriteArrayList<>();
    private final List<JsonNode> latestQueries = new CopyOnWriteArrayList<>();
    /** What /events/create answers, and whether /events/latest finds an open lifecycle. */
    private volatile int eventsStatus = 200;
    private volatile boolean lifecycleOpen = true;

    @BeforeEach
    void setUp() throws IOException {
        DriverManagerDataSource ds = new DriverManagerDataSource(
                SharedPostgres.newDatabase("run_coordinator_it_" + DATABASES.incrementAndGet()),
                SharedPostgres.username(), SharedPostgres.password());
        jdbc = new JdbcTemplate(ds);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(ds));
        flows = new FlowRepository(jdbc);
        SharedKvrocks.flush();
        kvrocks = new TenantKvrocks(SharedKvrocks.tenants(TENANT));
        runs = new RunStore(kvrocks, Duration.ofSeconds(1));
        TenantContext.setTenantId(TENANT);
        // The registry the loops read, with this tenant in it: what sweepAll() works over.
        TenantFeatures features = new TenantFeatures();
        features.setFlowFeatureEnabled(true);
        Tenant tenant = new Tenant();
        tenant.setOrganizationId(TENANT);
        tenant.setFeatures(features);
        registry = mock(TenantConfigService.class);
        registry.cachedTenants = new LinkedHashMap<>(Map.of(TENANT, tenant));
        schemaCheck = mock(FlowSchemaCheck.class);
        when(schemaCheck.isProvisioned(anyString())).thenReturn(true);

        datahub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        datahub.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            requests.add(exchange.getRequestMethod() + " " + path);
            bearers.add(String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")));
            int status = 200;
            String answer = "{\"items\":[]}";
            if (path.equals("/datasets/byids")) {
                // The check every run makes first, that its flow's dataset still exists.
                if (dataSetExists) {
                    answer = "{\"items\":[{\"id\":\"7\",\"externalId\":\"ds\"}]}";
                }
            } else if (path.equals("/events/create")) {
                JsonNode request = JSON.readTree(exchange.getRequestBody().readAllBytes());
                status = eventsStatus;
                if (status == 200) {
                    createdEvents.add(request.get("items").get(0));
                    answer = JSON.writeValueAsString(request);
                } else {
                    answer = "{\"type\":\"about:blank\",\"status\":" + status + "}";
                }
            } else if (path.equals("/events/latest")) {
                latestQueries.add(JSON.readTree(exchange.getRequestBody().readAllBytes()));
                if (lifecycleOpen) {
                    answer = "{\"items\":[{\"id\":\"0190b4c2-0000-7000-8000-000000000000\","
                            + "\"externalId\":\"flow:x\",\"type\":\"flow\",\"status\":\"open\","
                            + "\"eventTime\":\"2026-10-12T10:00:00Z\"}]}";
                }
            } else {
                requestArrivals.add(System.nanoTime());
            }
            byte[] body = answer.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", status == 200 ? "application/json"
                    : "application/problem+json");
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        datahub.start();

        heartbeats = Executors.newSingleThreadScheduledExecutor();
        heartbeats.scheduleWithFixedDelay(() -> heartbeating.forEach(c -> {
            try {
                c.heartbeatAll();
            } catch (RuntimeException ignored) {
                // a failed heartbeat is what self-fencing is for
            }
        }), HEARTBEAT.toMillis(), HEARTBEAT.toMillis(), TimeUnit.MILLISECONDS);
    }

    @AfterEach
    void tearDown() {
        heartbeats.shutdownNow();
        coordinators.forEach(RunCoordinator::shutdown);
        datahub.stop(0);
        RUNNING.clear();
        MOST.clear();
        RELEASED.clear();
        TenantContext.clear();
        kvrocks.close();
    }

    // ---------------------------------------------------------------- the tests

    @Test
    void twoInstancesClaimingAtOnceNeverTakeTheSameRun() throws Exception {
        long flow = deployedFlow("many", wait(50, "many"), 40, 1);
        for (int i = 0; i < 30; i++) {
            pending(flow, i);
        }
        RunCoordinator a = coordinator("a", 16);
        RunCoordinator b = coordinator("b", 16);

        List<UUID> claimedByA = Collections.synchronizedList(new ArrayList<>());
        List<UUID> claimedByB = Collections.synchronizedList(new ArrayList<>());
        Thread ta = Thread.ofVirtual().start(() -> TenantContext.runWith(TENANT, () -> claimRepeatedly(a, claimedByA)));
        Thread tb = Thread.ofVirtual().start(() -> TenantContext.runWith(TENANT, () -> claimRepeatedly(b, claimedByB)));
        ta.join();
        tb.join();

        Set<UUID> all = new HashSet<>(claimedByA);
        all.addAll(claimedByB);
        assertThat(claimedByA.size() + claimedByB.size()).isEqualTo(30).isEqualTo(all.size());
        assertThat(claimedByA).isNotEmpty();
        assertThat(claimedByB).isNotEmpty();
        await(() -> count("SUCCEEDED") == 30);
        assertThat(attemptsOfAllRuns()).as("no run needed a second attempt").containsOnly("1");
    }

    @Test
    void maxConcurrentRunsHoldsAcrossInstances() throws Exception {
        long flow = deployedFlow("limited", wait(400, "limited"), 2, 1);
        for (int i = 0; i < 6; i++) {
            pending(flow, i);
        }
        RunCoordinator a = coordinator("a", 8);
        RunCoordinator b = coordinator("b", 8);
        heartbeating.add(a);
        heartbeating.add(b);

        long until = System.nanoTime() + Duration.ofSeconds(8).toNanos();
        while (count("SUCCEEDED") < 6 && System.nanoTime() < until) {
            Thread ta = Thread.ofVirtual().start(() -> TenantContext.runWith(TENANT, () -> a.claim(TENANT, null)));
            Thread tb = Thread.ofVirtual().start(() -> TenantContext.runWith(TENANT, () -> b.claim(TENANT, null)));
            ta.join();
            tb.join();
            Thread.sleep(50);
        }

        assertThat(count("SUCCEEDED")).isEqualTo(6);
        assertThat(MOST.get("limited").get()).isEqualTo(2);
    }

    @Test
    void anInstanceThatStopsRenewingLosesItsRunToAnotherWhichRunsTheNextAttempt() throws Exception {
        long flow = deployedFlow("takeover", wait(6000, "takeover"), 1, 2);
        UUID run = pending(flow, 0);
        RunCoordinator a = coordinator("a", 1);
        RunCoordinator b = coordinator("b", 1);
        heartbeating.add(b);

        assertThat(a.claim(TENANT, null)).containsExactly(run);
        // a never renews: after the lease, the reaper on b takes the run back.
        Thread.sleep(LEASE.plusMillis(300).toMillis());
        b.reap(TENANT);
        StoredRun requeued = runs.find(run).orElseThrow();
        assertThat(requeued.status()).isEqualTo("PENDING");
        assertThat(requeued.attempt()).isEqualTo(2);
        assertThat(requeued.errorCode()).isEqualTo("LEASE_LOST");

        RELEASED.add("takeover");
        assertThat(b.claim(TENANT, null)).containsExactly(run);
        await(() -> "SUCCEEDED".equals(runs.find(run).orElseThrow().status()));
        // a's own attempt notices the lost lease and records nothing.
        await(() -> a.running() == 0);
        StoredRun done = runs.find(run).orElseThrow();
        assertThat(done.attempt()).isEqualTo(2);
        assertThat(done.status()).isEqualTo("SUCCEEDED");
    }

    @Test
    void aRunIsFinishedOnlyOnceItsRecordsArePublished() {
        long flow = deployedFlow("recorded", wait(0, "recorded"), 1, 2);
        UUID run = pending(flow, 0);
        RunCoordinator a = coordinator("a", 1);

        a.claim(TENANT, null);
        await(() -> "SUCCEEDED".equals(runs.find(run).orElseThrow().status()));
        assertThat(published).singleElement().satisfies(batch -> {
            assertThat(batch.getRunId()).isEqualTo(run.toString());
            assertThat(batch.getAttempt()).isEqualTo(1);
            assertThat(batch.getSequence()).isZero();
            assertThat(batch.getFlowExternalId()).isEqualTo("recorded");
            assertThat(batch.getFlowVersion()).isEqualTo(1);
            assertThat(batch.getRecords()).extracting(FlowRecordBatch.Row::getAction)
                    .containsExactly("create", "route", "drop");
        });

        // Records Pulsar does not take are not durable: the attempt fails, and is retried.
        failPublishing = true;
        UUID second = pending(flow, 1);
        a.claim(TENANT, null);
        await(() -> runs.find(second).orElseThrow().attempt() == 2);
        assertThat(runs.find(second).orElseThrow().errorCode()).isEqualTo(RunCoordinator.RECORDS_UNPUBLISHED);
    }

    @Test
    void onlyTheOwnerOfTheCurrentAttemptMayFinishIt() {
        long flow = deployedFlow("stale", wait(0, "stale"), 1, 2);
        UUID run = pending(flow, 0);
        runs.claimOne(run, "owner-1", LEASE, Duration.ofMinutes(1));
        RunStore.Outcome outcome = new RunStore.Outcome(null, null, null, null, "{}");

        assertThat(runs.complete(run, "owner-2", 1, "SUCCEEDED", false, outcome)).isFalse();
        assertThat(runs.complete(run, "owner-1", 2, "SUCCEEDED", false, outcome)).isFalse();
        assertThat(runs.retry(run, "owner-2", 1, Duration.ZERO, outcome)).isFalse();
        assertThat(runs.find(run).orElseThrow().status()).isEqualTo("RUNNING");
        assertThat(runs.complete(run, "owner-1", 1, "SUCCEEDED", false, outcome)).isTrue();
    }

    @Test
    void aRunThatLostItsLeaseSendsNothingMoreToDatahub() throws Exception {
        long flow = deployedFlow("fenced", call(5000), 1, 1);
        pending(flow, 0);
        RunCoordinator a = coordinator("a", 1);

        long claimedAt = System.nanoTime();
        a.claim(TENANT, null);
        // No heartbeat: the lease runs out two seconds after the claim.
        await(() -> a.running() == 0);

        assertThat(requestArrivals).isNotEmpty();
        long leaseEnd = claimedAt + LEASE.toNanos();
        assertThat(requestArrivals).allSatisfy(at -> assertThat(at).isLessThan(leaseEnd));
        int sent = requestArrivals.size();
        Thread.sleep(300);
        assertThat(requestArrivals).hasSize(sent);
    }

    @Test
    void aFailedAttemptIsRetriedAfterItsBackOffAndTheLastOneFailsTheRun() {
        long flow = deployedFlow("failing", fail(), 1, 2);
        UUID run = pending(flow, 0);
        RunCoordinator a = coordinator("a", 1, Duration.ofMillis(900));

        a.claim(TENANT, null);
        await(() -> "PENDING".equals(runs.find(run).orElseThrow().status()));
        StoredRun retried = runs.find(run).orElseThrow();
        assertThat(retried.attempt()).isEqualTo(2);
        assertThat(retried.errorCode()).isEqualTo("PROCESSOR_FAILED");
        assertThat(retried.notBefore()).isAfter(runs.now());
        assertThat(a.claim(TENANT, null)).as("not before its back-off is over").isEmpty();

        sleep(1000);
        a.claim(TENANT, null);
        await(() -> "FAILED".equals(runs.find(run).orElseThrow().status()));
        StoredRun failed = runs.find(run).orElseThrow();
        assertThat(failed.attempt()).isEqualTo(2);
        assertThat(failed.finishedAt()).isNotNull();

        // The run-failed event is written as soon as the run ends, and the debt cleared.
        await(() -> !runs.find(run).orElseThrow().failureEventOwed());
        assertThat(createdEvents).singleElement().satisfies(event -> {
            assertThat(event.get("externalId").asString())
                    .isEqualTo("flow:failing:" + failed.window().start());
            assertThat(event.get("type").asString()).isEqualTo("flow");
            assertThat(event.get("subType").asString()).isEqualTo("run-failed");
            assertThat(event.get("status").asString()).isEqualTo("open");
            assertThat(event.get("dataSetId").asString()).isEqualTo("7");
            assertThat(Instant.parse(event.get("eventTime").asString()).toEpochMilli())
                    .isEqualTo(failed.finishedAt().toEpochMilli());
            assertThat(event.get("metadata").get("runId").asString()).isEqualTo(run.toString());
            assertThat(event.get("metadata").get("attempts").asString()).isEqualTo("2");
            assertThat(event.get("metadata").get("errorCode").asString()).isEqualTo("PROCESSOR_FAILED");
            assertThat(event.get("metadata").get("windowStart").asString())
                    .isEqualTo(failed.window().start().toString());
        });
    }

    @Test
    void aFailureEventTheApiCannotTakeIsOwedUntilASweepWritesIt() {
        long flow = deployedFlow("unreported", fail(), 1, 1);
        UUID run = pending(flow, 0);
        RunCoordinator a = coordinator("a", 1);
        eventsStatus = 503;

        a.claim(TENANT, null);
        await(() -> "FAILED".equals(runs.find(run).orElseThrow().status()));
        assertThat(runs.find(run).orElseThrow().failureEventOwed()).isTrue();
        assertThat(createdEvents).isEmpty();

        a.sweep(TENANT);
        assertThat(runs.find(run).orElseThrow().failureEventOwed()).isTrue();

        eventsStatus = 200;
        coordinator("b", 1).sweep(TENANT);
        assertThat(runs.find(run).orElseThrow().failureEventOwed()).isFalse();
        assertThat(createdEvents).singleElement()
                .satisfies(event -> assertThat(event.get("status").asString()).isEqualTo("open"));

        // Paid once: a later sweep writes nothing more.
        a.sweep(TENANT);
        assertThat(createdEvents).hasSize(1);
    }

    @Test
    void aFailureEventTheApiRefusesForGoodIsNotRetried() {
        long flow = deployedFlow("refused-event", fail(), 1, 1);
        UUID run = pending(flow, 0);
        eventsStatus = 400;

        coordinator("a", 1).claim(TENANT, null);
        await(() -> "FAILED".equals(runs.find(run).orElseThrow().status()));
        await(() -> !runs.find(run).orElseThrow().failureEventOwed());
        assertThat(createdEvents).isEmpty();
    }

    @Test
    void aSuccessForAWindowThatFailedResolvesItsOpenLifecycle() {
        long flow = deployedFlow("recovering", wait(0, "recovering"), 1, 1);
        failedRun(flow, 0);
        UUID rerun = pending(flow, 0);

        coordinator("a", 1).claim(TENANT, rerun);
        await(() -> "SUCCEEDED".equals(runs.find(rerun).orElseThrow().status()));
        await(() -> !runs.find(rerun).orElseThrow().failureEventOwed());

        StoredRun succeeded = runs.find(rerun).orElseThrow();
        String lifecycle = "flow:recovering:" + succeeded.window().start();
        assertThat(latestQueries).singleElement().satisfies(query -> {
            assertThat(query.get("filter").get("externalId").get(0).asString()).isEqualTo(lifecycle);
            assertThat(query.get("filter").get("subType").get(0).asString()).isEqualTo("run-failed");
            assertThat(query.get("status").get(0).asString()).isEqualTo("open");
        });
        assertThat(createdEvents).singleElement().satisfies(event -> {
            assertThat(event.get("externalId").asString()).isEqualTo(lifecycle);
            assertThat(event.get("status").asString()).isEqualTo("resolved");
            assertThat(event.get("metadata").get("runId").asString()).isEqualTo(rerun.toString());
        });
    }

    @Test
    void aSuccessLeavesALifecycleThatIsNoLongerOpenAlone() {
        long flow = deployedFlow("dismissed", wait(0, "dismissed"), 1, 1);
        failedRun(flow, 0);
        UUID rerun = pending(flow, 0);
        lifecycleOpen = false;

        coordinator("a", 1).claim(TENANT, rerun);
        await(() -> "SUCCEEDED".equals(runs.find(rerun).orElseThrow().status()));
        await(() -> !runs.find(rerun).orElseThrow().failureEventOwed());
        assertThat(latestQueries).hasSize(1);
        assertThat(createdEvents).isEmpty();
    }

    @Test
    void aSuccessForAWindowThatNeverFailedAsksNothing() {
        long flow = deployedFlow("healthy", wait(0, "healthy"), 1, 1);
        UUID run = pending(flow, 0);

        coordinator("a", 1).claim(TENANT, null);
        await(() -> "SUCCEEDED".equals(runs.find(run).orElseThrow().status()));
        assertThat(runs.find(run).orElseThrow().failureEventOwed()).isFalse();
        assertThat(latestQueries).isEmpty();
        assertThat(createdEvents).isEmpty();
    }

    @Test
    void aCancelledRunStopsAtItsNextHeartbeatAndAPendingOneAtOnce() throws Exception {
        long flow = deployedFlow("cancel", wait(10_000, "cancel"), 2, 2);
        UUID running = pending(flow, 0);
        UUID waiting = pending(flow, 1);
        RunCoordinator a = coordinator("a", 1);
        heartbeating.add(a);

        assertThat(a.claim(TENANT, running)).containsExactly(running);
        assertThat(runs.cancelPending(waiting, "CANCELLED", "Cancelled by user-1.")).isTrue();
        assertThat(runs.find(waiting).orElseThrow().status()).isEqualTo("CANCELLED");

        assertThat(runs.requestCancel(running)).isTrue();
        await(() -> "CANCELLED".equals(runs.find(running).orElseThrow().status()));
        assertThat(runs.find(running).orElseThrow().attempt()).isEqualTo(1);
    }

    @Test
    void aRunPastItsTimeoutFailsWithTimeout() {
        long flow = deployedFlow("slow", wait(10_000, "slow"), 1, 1);
        UUID run = pending(flow, 0, Duration.ofSeconds(1));
        RunCoordinator a = coordinator("a", 1);
        heartbeating.add(a);

        a.claim(TENANT, null);
        await(() -> "FAILED".equals(runs.find(run).orElseThrow().status()));
        assertThat(runs.find(run).orElseThrow().errorCode()).isEqualTo(RunCoordinator.TIMEOUT);
    }

    @Test
    void aRunOfAFlowSwitchedOffIsCancelledInsteadOfStarted() {
        long flow = deployedFlow("off", wait(0, "off"), 1, 1);
        UUID run = pending(flow, 0);
        describe(flow, false, 1);

        assertThat(coordinator("a", 1).claim(TENANT, null)).isEmpty();
        StoredRun cancelled = runs.find(run).orElseThrow();
        assertThat(cancelled.status()).isEqualTo("CANCELLED");
        assertThat(cancelled.errorCode()).isEqualTo(RunCoordinator.FLOW_DISABLED);
    }

    @Test
    void aRunWithoutAnIdentityFailsWithAuth() {
        long flow = deployedFlow("anonymous", wait(0, "anonymous"), 1, 1);
        UUID run = pending(flow, 0);
        RunCoordinator a = coordinator("a", 1, Duration.ZERO, (tenant, runAs) -> {
            throw new RunIdentityException("No identity configured.");
        });

        a.claim(TENANT, null);
        await(() -> "FAILED".equals(runs.find(run).orElseThrow().status()));
        assertThat(runs.find(run).orElseThrow().errorCode()).isEqualTo(RunCoordinator.AUTH);
    }

    @Test
    void anIdentityRefusedMidRunFailsItWithoutRetrying() {
        long flow = deployedFlow("revoked", wait(0, "revoked"), 1, 3);
        UUID run = pending(flow, 0);
        // The token was good when the run started; Keycloak refuses it at the first request.
        RunCoordinator a = coordinator("a", 1, Duration.ZERO, (tenant, flowId) -> () -> {
            throw new RunIdentityException("Keycloak refused the flow's identity.", true);
        });

        a.claim(TENANT, null);
        await(() -> "FAILED".equals(runs.find(run).orElseThrow().status()));
        StoredRun failed = runs.find(run).orElseThrow();
        assertThat(failed.errorCode()).isEqualTo(RunCoordinator.IDENTITY_REJECTED);
        assertThat(failed.attempt()).isEqualTo(1);
    }

    @Test
    void aRefusedIdentityFailsTheRunWithoutRetrying() {
        long flow = deployedFlow("refused", wait(0, "refused"), 1, 3);
        UUID run = pending(flow, 0);
        RunCoordinator a = coordinator("a", 1, Duration.ZERO, (tenant, flowId) -> {
            throw new RunIdentityException("Keycloak refused the flow's identity.", true);
        });

        a.claim(TENANT, null);
        await(() -> "FAILED".equals(runs.find(run).orElseThrow().status()));
        StoredRun failed = runs.find(run).orElseThrow();
        assertThat(failed.errorCode()).isEqualTo(RunCoordinator.IDENTITY_REJECTED);
        assertThat(failed.attempt()).isEqualTo(1);
        // The refused identity cannot write its own failure event: it waits, owed, for the flow
        // to be deployed afresh, and sweeps leave it alone until then.
        assertThat(failed.failureEventOwed()).isTrue();
        flows.needsReauthorisation(flow, "invalid_grant");
        a.sweep(TENANT);
        assertThat(runs.find(run).orElseThrow().failureEventOwed()).isTrue();
        assertThat(createdEvents).isEmpty();
    }

    @Test
    void aFlowWhoseDatasetWasDeletedIsSwitchedOffAndItsRunEnds() {
        long flow = deployedFlow("orphan", wait(0, "orphan"), 1, 2);
        UUID run = pending(flow, 0);
        dataSetExists = false;

        coordinator("a", 1).claim(TENANT, null);
        await(() -> "CANCELLED".equals(runs.find(run).orElseThrow().status()));
        StoredRun cancelled = runs.find(run).orElseThrow();
        assertThat(cancelled.errorCode()).isEqualTo(RunCoordinator.DATASET_DELETED);
        assertThat(cancelled.failureEventOwed()).isFalse();
        assertThat(jdbc.queryForObject("SELECT disabled_reason FROM flow WHERE id = ?", String.class,
                flow)).isEqualTo(RunCoordinator.DATASET_DELETED);
        assertThat(jdbc.queryForObject("SELECT enabled FROM flow WHERE id = ?", Boolean.class, flow))
                .isFalse();
        assertThat(SharedKvrocks.raw().hget("flow:meta:" + flow, "enabled")).as("the run store was told at once")
                .isEqualTo("0");
    }

    // ---------------------------------------------------------------- previews

    @Test
    void aPreviewRunsOnlyWhereItWasStartedAsItsCallerAndWritesNothing() {
        // Saved, never deployed: a preview tries a flow out whether or not it is switched on.
        long flow = savedFlow("previewed", points() + "," + sink(),
                "{ \"from\": \"p\", \"relationship\": \"success\", \"to\": \"out\" }");
        UUID run = preview(flow, 1, "previewed", null, 7L);
        RunCoordinator a = coordinator("a", 1);
        RunCoordinator b = coordinator("b", 1);

        // No sweep takes it: the caller's token it runs with exists only where it was started.
        assertThat(b.claim(TENANT, null)).isEmpty();
        assertThat(a.claim(TENANT, null)).isEmpty();
        assertThat(a.claimPreview(TENANT, run, () -> "caller-token")).isTrue();
        await(() -> "SUCCEEDED".equals(runs.find(run).orElseThrow().status()));

        assertThat(requests).noneMatch(r -> r.startsWith("POST /timeseries/data")
                || r.startsWith("POST /timeseries/create") || r.startsWith("POST /events/create"));
        assertThat(bearers).isNotEmpty().allMatch("Bearer caller-token"::equals);
        StoredRun done = runs.find(run).orElseThrow();
        assertThat(done.preview()).isTrue();
        assertThat(done.failureEventOwed()).isFalse();
        assertThat(done.stats()).contains("Would write 3 points to ts_out");
        // Its records are kept like any run's, for the console to show.
        assertThat(published).isNotEmpty()
                .allSatisfy(batch -> assertThat(batch.getRunId()).isEqualTo(run.toString()));
        // Nor does it count against the flow's runs.
        assertThat(flows.deployment(flow)).isEmpty();
    }

    @Test
    void aFailedPreviewRaisesNoEventAndIsNeverQueuedAgain() throws Exception {
        UUID failed = unsavedPreview("unsaved_fail", fail());
        RunCoordinator a = coordinator("a", 1);
        assertThat(a.claimPreview(TENANT, failed, () -> "caller-token")).isTrue();
        await(() -> "FAILED".equals(runs.find(failed).orElseThrow().status()));
        StoredRun f = runs.find(failed).orElseThrow();
        assertThat(f.attempt()).isEqualTo(1);
        assertThat(f.failureEventOwed()).isFalse();
        assertThat(createdEvents).isEmpty();

        // An instance that stops renewing loses its preview for good: no one else has the token.
        UUID lost = unsavedPreview("unsaved_lost", wait(6000, "unsaved_lost"));
        RunCoordinator c = coordinator("c", 1);
        RunCoordinator b = coordinator("b", 1);
        assertThat(c.claimPreview(TENANT, lost, () -> "caller-token")).isTrue();
        Thread.sleep(LEASE.plusMillis(300).toMillis());
        b.reap(TENANT);
        StoredRun l = runs.find(lost).orElseThrow();
        assertThat(l.status()).isEqualTo("FAILED");
        assertThat(l.errorCode()).isEqualTo("LEASE_LOST");
        assertThat(l.failureEventOwed()).isFalse();
        assertThat(b.claim(TENANT, null)).isEmpty();
        b.sweep(TENANT);
        assertThat(createdEvents).isEmpty();
    }

    @Test
    void aPreviewIsNotHandedBackWhenItsInstanceShutsDown() {
        UUID run = unsavedPreview("unsaved_shutdown", """
                { "id": "h", "type": "test.hang" }
                """);
        RunCoordinator a = coordinator("a", 1);
        heartbeating.add(a);
        assertThat(a.claimPreview(TENANT, run, () -> "caller-token")).isTrue();
        await(() -> a.running() == 1);

        a.shutdown();
        StoredRun stopped = runs.find(run).orElseThrow();
        assertThat(stopped.status()).isEqualTo("CANCELLED");
        assertThat(stopped.errorCode()).isEqualTo(RunCoordinator.SHUTDOWN);
    }

    @Test
    void aPreviewOfAnUnsavedDefinitionHasItsOwnDatasetAndIsNotAFlowsRun() {
        UUID run = unsavedPreview("unsaved_seen", wait(0, "unsaved_seen"));
        long saved = savedFlow("saved_flow", wait(0, "saved_flow"), "");
        runs.syncFlow(new FlowLimits(saved, "saved_flow", 7, true, true, 1));
        UUID real = pending(saved, 0);

        StoredRun stored = runs.find(run).orElseThrow();
        assertThat(stored.flowId()).isNull();
        assertThat(stored.dataSetId()).isEqualTo(7);
        assertThat(stored.flowExternalId()).isEqualTo("unsaved_seen");
        assertThat(stored.preview()).isTrue();
        assertThat(runs.liveNewest(10)).extracting(StoredRun::id).containsExactlyInAnyOrder(run, real);
        // Nobody's run: no flow counts it, and no claim takes it.
        assertThat(runs.states(List.of(saved)).get(saved).queued()).isEqualTo(1);
        assertThat(runs.claim("a", LEASE, 5, Duration.ofMinutes(1))).extracting(RunStore.Claimed::id)
                .containsExactly(real);
    }

    @Test
    void whatEachFlowHasInHandIsWhatTheOverviewReads() {
        long busy = deployedFlow("busy", wait(0, "busy"), 2, 1);
        UUID going = pending(busy, 0);
        UUID waiting = pending(busy, 1);
        UUID retrying = pending(busy, 2);
        runs.claimOne(going, "instance-a", LEASE, Duration.ofMinutes(1));
        runs.claimOne(retrying, "instance-a", LEASE, Duration.ofMinutes(1));
        runs.retry(retrying, "instance-a", 1, Duration.ofMinutes(5), new RunStore.Outcome(null, null, null, null, "{}"));
        long failing = deployedFlow("failing", wait(0, "failing"), 1, 1);
        UUID failed = pending(failing, 0);
        runs.claimOne(failed, "instance-a", LEASE, Duration.ofMinutes(1));
        runs.complete(failed, "instance-a", 1, "FAILED", true,
                new RunStore.Outcome("PROCESSOR_FAILED", "boom", null, null, "{}"));
        UUID later = pending(failing, 1);
        runs.claimOne(later, "instance-b", LEASE, Duration.ofMinutes(1));
        runs.complete(later, "instance-b", 1, "SUCCEEDED", false, new RunStore.Outcome(null, null, null, null, "{}"));
        long never = savedFlow("never_deployed", wait(0, "never_deployed"), "");
        unsavedPreview("board_preview", wait(0, "board_preview"));

        Map<Long, RunStore.FlowState> states = runs.states(List.of(busy, failing, never));

        RunStore.FlowState b = states.get(busy);
        assertThat(b.running()).isEqualTo(1);
        assertThat(b.runningOn()).isEqualTo("instance-a");
        assertThat(b.runningSince()).isNotNull();
        assertThat(b.queued()).as("the waiting one, and the one waiting out its back-off").isEqualTo(2);
        assertThat(b.retrying()).isEqualTo(1);
        assertThat(b.oldestWaiting()).isNotNull();
        assertThat(b.last()).isNull();
        RunStore.FlowState f = states.get(failing);
        assertThat(f.running()).isZero();
        assertThat(f.queued()).isZero();
        // The latest run to end, whichever way.
        assertThat(f.last().id()).isEqualTo(later);
        assertThat(f.last().status()).isEqualTo("SUCCEEDED");
        assertThat(states.get(never).queued()).isZero();
        assertThat(waiting).isNotNull();
    }

    // ---------------------------------------------------------------- what Postgres tells the run store

    @Test
    void theSweepCopiesAFlowsLimitsFromPostgresToTheRunStore() {
        long flow = storedFlow("copied", wait(0, "copied"), 3, 1);
        assertThat(SharedKvrocks.raw().hgetall("flow:meta:" + flow)).as("not told yet").isEmpty();

        coordinator("a", 0).sweepAll();

        assertThat(SharedKvrocks.raw().hgetall("flow:meta:" + flow)).containsEntry("external_id", "copied")
                .containsEntry("data_set_id", "7").containsEntry("enabled", "1").containsEntry("deployed", "1")
                .containsEntry("max_concurrent", "3");
    }

    @Test
    void aDeploymentIsToldToTheRunStoreAtOnceWithoutWaitingForASweep() {
        long flow = storedFlow("at_once", wait(0, "at_once"), 2, 1);
        UUID run = pending(flow, 0);
        RunCoordinator a = coordinator("a", 2);

        a.syncFlow(flow);

        assertThat(SharedKvrocks.raw().hget("flow:meta:" + flow, "max_concurrent")).isEqualTo("2");
        assertThat(a.claim(TENANT, null)).containsExactly(run);
    }

    @Test
    void aLimitRaisedInPostgresReachesTheRunStoreAtTheNextSweepAndStartsWhatWasWaiting() {
        long flow = storedFlow("raised", wait(60_000, "raised"), 1, 1);
        pending(flow, 0);
        pending(flow, 1);
        RunCoordinator a = coordinator("a", 4);
        heartbeating.add(a);

        a.sweepAll();
        await(() -> RUNNING.getOrDefault("raised", new AtomicInteger()).get() == 1);
        a.sweepAll();
        assertThat(count("RUNNING")).as("the limit of one holds").isEqualTo(1);
        assertThat(count("PENDING")).isEqualTo(1);

        jdbc.update("UPDATE flow SET max_concurrent_runs = 2 WHERE id = ?", flow);
        a.sweepAll();

        assertThat(SharedKvrocks.raw().hget("flow:meta:" + flow, "max_concurrent")).isEqualTo("2");
        await(() -> count("RUNNING") == 2);
        RELEASED.add("raised");
        await(() -> count("SUCCEEDED") == 2);
    }

    @Test
    void aLimitLoweredInPostgresStopsStartingRunsUntilTheOnesRunningEnd() {
        long flow = storedFlow("lowered", wait(60_000, "lowered"), 2, 1);
        pending(flow, 0);
        pending(flow, 1);
        pending(flow, 2);
        RunCoordinator a = coordinator("a", 4);
        heartbeating.add(a);
        a.startDispatching();
        a.sweepAll();
        await(() -> count("RUNNING") == 2);

        jdbc.update("UPDATE flow SET max_concurrent_runs = 1 WHERE id = ?", flow);
        a.sweepAll();
        assertThat(count("RUNNING")).as("the two running carry on").isEqualTo(2);
        assertThat(count("PENDING")).isEqualTo(1);

        RELEASED.add("lowered");
        await(() -> count("SUCCEEDED") == 3);
    }

    @Test
    void aFlowSwitchedOffInPostgresHasItsWaitingRunsCancelledAtTheNextSweep() {
        long flow = storedFlow("switched_off", wait(0, "switched_off"), 1, 1);
        RunCoordinator a = coordinator("a", 2);
        a.sweepAll();
        UUID waiting = pending(flow, 0);

        flows.disable(flow, "switched off by test");
        a.sweepAll();

        assertThat(SharedKvrocks.raw().hget("flow:meta:" + flow, "enabled")).isEqualTo("0");
        StoredRun cancelled = runs.find(waiting).orElseThrow();
        assertThat(cancelled.status()).isEqualTo("CANCELLED");
        assertThat(cancelled.errorCode()).isEqualTo(RunCoordinator.FLOW_DISABLED);
    }

    @Test
    void aFlowDeletedInPostgresIsNoLongerStartedFrom() {
        long flow = storedFlow("deleted_flow", wait(0, "deleted_flow"), 1, 1);
        RunCoordinator a = coordinator("a", 2);
        a.sweepAll();
        assertThat(SharedKvrocks.raw().hget("flow:meta:" + flow, "deployed")).isEqualTo("1");

        runs.purgeFlow(flow);
        jdbc.update("DELETE FROM flow WHERE id = ?", flow);
        a.sweepAll();

        assertThat(SharedKvrocks.keys("flow:*:" + flow)).as("nothing of it is left or told again").isEmpty();
    }

    @Test
    void aQuietSweepAsksPostgresNothingAboutTheFlowsAndNothingAtAllAboutTheClock() {
        resync = Duration.ofMinutes(10);
        flows = org.mockito.Mockito.spy(flows);
        long flow = storedFlow("quiet", wait(0, "quiet"), 2, 1);
        RunCoordinator a = coordinator("a", 0);

        for (int sweep = 0; sweep < 5; sweep++) {
            a.sweepAll();
        }

        org.mockito.Mockito.verify(flows, org.mockito.Mockito.times(1)).deployedFlows();
        org.mockito.Mockito.verify(flows, org.mockito.Mockito.never()).now();
        assertThat(SharedKvrocks.raw().hget("flow:meta:" + flow, "max_concurrent")).isEqualTo("2");
    }

    @Test
    void aChangeMadeBehindTheServicesBackAndAKvrocksThatLostItsFlowsAreHealedAtTheNextResync() {
        resync = Duration.ofMinutes(10);
        long flow = storedFlow("healed", wait(0, "healed"), 2, 1);
        RunCoordinator a = coordinator("a", 0);
        a.sweepAll();
        jdbc.update("UPDATE flow SET max_concurrent_runs = 5 WHERE id = ?", flow);
        SharedKvrocks.raw().hset("flow:meta:" + flow, "max_concurrent", "9");

        a.sweepAll();
        assertThat(SharedKvrocks.raw().hget("flow:meta:" + flow, "max_concurrent")).as("not yet").isEqualTo("9");

        skipped.addAndGet(Duration.ofMinutes(11).toNanos());
        a.sweepAll();
        assertThat(SharedKvrocks.raw().hget("flow:meta:" + flow, "max_concurrent")).isEqualTo("5");

        SharedKvrocks.flush();
        a.sweepAll();
        assertThat(SharedKvrocks.raw().hgetall("flow:meta:" + flow)).as("not yet").isEmpty();
        skipped.addAndGet(Duration.ofMinutes(11).toNanos());
        a.sweepAll();
        assertThat(SharedKvrocks.raw().hget("flow:meta:" + flow, "max_concurrent")).isEqualTo("5");
    }

    @Test
    void aTenantsFirstSweepTellsTheRunStoreItsFlowsWithoutWaitingForAResync() {
        resync = Duration.ofHours(1);
        long flow = storedFlow("first", wait(0, "first"), 3, 1);

        coordinator("a", 0).sweepAll();

        assertThat(SharedKvrocks.raw().hget("flow:meta:" + flow, "max_concurrent")).isEqualTo("3");
    }

    @Test
    void whatTheRunStoreHoldsOfARunIsWhatTheOverviewAndTheRunPageRead() {
        long flow = storedFlow("visible", wait(60_000, "visible"), 1, 1);
        UUID first = pending(flow, 0);
        UUID second = pending(flow, 1);
        RunCoordinator a = coordinator("a", 2);
        heartbeating.add(a);
        a.startDispatching();
        a.sweepAll();
        await(() -> RUNNING.getOrDefault("visible", new AtomicInteger()).get() == 1);

        RunStore.FlowState state = runs.states(List.of(flow)).get(flow);
        assertThat(state.running()).isEqualTo(1);
        assertThat(state.queued()).isEqualTo(1);
        assertThat(state.runningOn()).isEqualTo("a");
        assertThat(runs.find(first).orElseThrow().status()).isEqualTo("RUNNING");
        assertThat(runs.find(second).orElseThrow().status()).isEqualTo("PENDING");

        RELEASED.add("visible");
        await(() -> count("SUCCEEDED") == 2);
        state = runs.states(List.of(flow)).get(flow);
        assertThat(state.running()).isZero();
        assertThat(state.queued()).isZero();
        assertThat(state.last().status()).isEqualTo("SUCCEEDED");
        assertThat(runs.find(second).orElseThrow().finishedAt()).isNotNull();
    }

    // ---------------------------------------------------------------- failures, reboots and backlogs

    @Test
    void aRetryWaitIsSpreadOverItsUpperHalfAndNeverPastTheMaximum() {
        RunCoordinator a = coordinator("a", 1, Duration.ofSeconds(10));
        for (int attempt : new int[]{1, 2, 5, 20}) {
            Duration nominal = Duration.ofSeconds(10).multipliedBy(1L << Math.min(attempt - 1, 20));
            if (nominal.compareTo(Duration.ofHours(6)) > 0) {
                nominal = Duration.ofHours(6);
            }
            Set<Duration> seen = new HashSet<>();
            for (int i = 0; i < 500; i++) {
                Duration wait = a.backoff(attempt);
                assertThat(wait).isBetween(nominal.dividedBy(2), nominal);
                seen.add(wait);
            }
            assertThat(seen).as("attempt " + attempt + " waits differ").hasSizeGreaterThan(100);
        }
    }

    /**
     * Something every run of a flow depends on is down: all of them fail at once, and again at each
     * retry. The flow's runs all end failed after their attempts, in the open each waited a different
     * time before retrying, the flow of another is not held up by them, every run's failure is
     * written as an event, and the store is sound at the end. Two instances share the work.
     */
    @Test
    void aFailureStormEndsEveryFailingRunAfterItsAttemptsWithoutHoldingUpAHealthyFlow() throws Exception {
        int failing = 150;
        int healthy = 30;
        long down = deployedFlow("down", fail(), 4, 3);
        long fine = deployedFlow("fine", wait(0, "fine"), 4, 1);
        List<UUID> failingRuns = new ArrayList<>();
        List<UUID> healthyRuns = new ArrayList<>();
        for (int i = 0; i < failing; i++) {
            failingRuns.add(pending(down, i));
        }
        for (int i = 0; i < healthy; i++) {
            healthyRuns.add(pending(fine, i));
        }
        RunCoordinator a = coordinator("a", 8, Duration.ofMillis(40));
        RunCoordinator b = coordinator("b", 8, Duration.ofMillis(40));
        for (RunCoordinator c : List.of(a, b)) {
            heartbeating.add(c);
            c.startDispatching();
        }

        Set<String> retryTimes = new HashSet<>();
        long until = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        while ((count("FAILED") < failing || count("SUCCEEDED") < healthy) && System.nanoTime() < until) {
            a.sweepAll();
            b.sweepAll();
            a.reapAll();
            if (retryTimes.size() < 5) {
                // Who waits to retry, and until when: a spread, not one instant.
                SharedKvrocks.raw().zrangeWithScores("flow:delayed", 0, -1)
                        .forEach(v -> retryTimes.add(String.valueOf(v.getScore())));
            }
            sleep(50);
        }

        assertThat(count("FAILED")).isEqualTo(failing);
        assertThat(count("SUCCEEDED")).isEqualTo(healthy);
        assertThat(runs.find(failingRuns)).allSatisfy(run -> {
            assertThat(run.status()).isEqualTo("FAILED");
            assertThat(run.attempt()).as("every attempt was used and no more").isEqualTo(3);
        });
        assertThat(retryTimes).as("the retries did not all wait for the same instant").hasSizeGreaterThan(4);
        long lastHealthy = runs.find(healthyRuns).stream().mapToLong(r -> r.finishedAt().toEpochMilli()).max().orElseThrow();
        long lastFailing = runs.find(failingRuns).stream().mapToLong(r -> r.finishedAt().toEpochMilli()).max().orElseThrow();
        assertThat(lastHealthy).as("the healthy flow was not made to wait for the storm").isLessThan(lastFailing);
        // Events the api could not take at once are owed, and the sweeps pay them, some at a time.
        await(() -> {
            a.sweepAll();
            return SharedKvrocks.raw().scard("flow:owed") == 0;
        });
        // Writing is at least once, with an id made from the event itself, which the api takes as the
        // same event: a write that timed out and is paid again is not a second event.
        assertThat(createdEvents.stream().map(e -> e.get("id").asString()).distinct())
                .as("one event for each run that failed for good").hasSize(failing);
        assertThat(createdEvents.stream().map(e -> e.get("metadata").get("runId").asString()).collect(java.util.stream.Collectors.toSet()))
                .containsExactlyInAnyOrderElementsOf(failingRuns.stream().map(UUID::toString).toList());
        assertThat(StoreInvariants.violations()).isEmpty();
    }

    @Test
    void anInstanceThatCrashesLosesNoRunAndAnotherFinishesTheBacklog() {
        long flow = deployedFlow("crashing", wait(60_000, "crashing"), 3, 2);
        List<UUID> all = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            all.add(pending(flow, i));
        }
        RunCoordinator a = coordinator("a", 3);
        a.sweepAll();
        await(() -> count("RUNNING") == 3);
        // a is gone from here on: it renews nothing, and nobody heard it leave.
        RunCoordinator b = coordinator("b", 3);
        heartbeating.add(b);
        b.startDispatching();

        long until = System.nanoTime() + Duration.ofSeconds(40).toNanos();
        while (count("SUCCEEDED") < 12 && System.nanoTime() < until) {
            b.sweepAll();
            b.reapAll();
            if (runs.find(all).stream().anyMatch(r -> r.attempt() == 2)) {
                RELEASED.add("crashing");
            }
            sleep(100);
        }

        assertThat(count("SUCCEEDED")).isEqualTo(12);
        assertThat(runs.find(all)).extracting(StoredRun::attempt).as("only the three a held were run twice")
                .containsExactlyInAnyOrder(2, 2, 2, 1, 1, 1, 1, 1, 1, 1, 1, 1);
        assertThat(StoreInvariants.violations()).isEmpty();
    }

    @Test
    void anInstanceThatShutsDownHandsItsRunsBackWithoutSpendingAnAttempt() {
        long flow = deployedFlow("leaving", wait(60_000, "leaving"), 2, 2);
        UUID first = pending(flow, 0);
        UUID second = pending(flow, 1);
        RunCoordinator a = coordinator("a", 2);
        heartbeating.add(a);
        a.sweepAll();
        await(() -> count("RUNNING") == 2);

        a.shutdown();

        assertThat(runs.find(List.of(first, second))).allSatisfy(run -> {
            assertThat(run.status()).isEqualTo("PENDING");
            assertThat(run.attempt()).as("the attempt does not count").isEqualTo(1);
            assertThat(run.owner()).isNull();
        });
        RunCoordinator b = coordinator("b", 2);
        assertThat(b.claim(TENANT, null)).as("taken over at once, without waiting for a lease")
                .containsExactlyInAnyOrder(first, second);
        RELEASED.add("leaving");
        await(() -> count("SUCCEEDED") == 2);
        assertThat(runs.find(List.of(first, second))).extracting(StoredRun::attempt).containsOnly(1);
    }

    @Test
    void aCatchUpTooSmallForASweepsWorthOfTicksIsRefusedAtStartUp() {
        assertThat(RunCoordinator.requireCatchUp(20, Duration.ofSeconds(10))).isEqualTo(20);
        assertThat(RunCoordinator.requireCatchUp(60, Duration.ofSeconds(10))).isEqualTo(60);
        assertThat(RunCoordinator.requireCatchUp(2, Duration.ofSeconds(1))).isEqualTo(2);
        assertThat(RunCoordinator.requireCatchUp(2, Duration.ofMillis(500))).isEqualTo(2);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> RunCoordinator.requireCatchUp(19, Duration.ofSeconds(10)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("catch-up (19)").hasMessageContaining("at least 20");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> RunCoordinator.requireCatchUp(1, Duration.ofSeconds(1)))
                .isInstanceOf(IllegalStateException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> RunCoordinator.requireCatchUp(24, Duration.ofSeconds(30)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("30 ticks");
    }

    /**
     * Runs are queued by one instance, as the ticker does, and the others are not told: every instance
     * with a free slot still finds them within a moment, and the slots of all of them fill. Without
     * that, instances that did not make the runs sit idle until their own sweep.
     */
    @Test
    void instancesThatDidNotQueueTheRunsStillFillTheirSlotsWithoutWaitingForASweep() {
        long flow = deployedFlow("shared_backlog", wait(60_000, "shared_backlog"), 12, 1);
        for (int i = 0; i < 60; i++) {
            pending(flow, i);
        }
        RunCoordinator a = coordinator("a", 4);
        RunCoordinator b = coordinator("b", 4);
        RunCoordinator c = coordinator("c", 4);
        for (RunCoordinator each : List.of(a, b, c)) {
            heartbeating.add(each);
            each.startDispatching();
        }

        a.poke(TENANT);

        await(() -> a.running() == 4 && b.running() == 4 && c.running() == 4);
        assertThat(count("RUNNING")).isEqualTo(12);
        RELEASED.add("shared_backlog");
        await(() -> count("SUCCEEDED") == 60);
    }

    // ---------------------------------------------------------------- schedules and the ticker

    @Test
    void missedTicksAreCreatedOldestFirstAndClaimedEarliestWindowFirst() {
        long flow = deployedFlow("hourly", wait(0, "hourly"), 1, 1);
        Instant fiveHoursAgo = runs.now().minus(Duration.ofHours(5)).atZone(ZoneOffset.UTC)
                .withMinute(0).withSecond(0).withNano(0).toInstant();
        schedule(flow, "0 0 * * * *", Duration.ofHours(1), fiveHoursAgo);
        RunCoordinator a = coordinator("a", 1);

        assertThat(a.tick(TENANT)).isEqualTo(6);
        assertThat(a.tick(TENANT)).isZero();
        List<Instant> windows = windowEndsByCreation();
        assertThat(windows).hasSize(6).isSorted();
        assertThat(windows.getFirst()).isEqualTo(fiveHoursAgo);
        assertThat(runs.schedule(flow).orElseThrow().lastTick()).isEqualTo(windows.getLast());
        a.checkpoint(TENANT);
        assertThat(jdbc.queryForObject("SELECT last_tick FROM flow WHERE id = ?", Timestamp.class, flow)
                .toInstant()).as("saved to Postgres at the checkpoint").isEqualTo(windows.getLast());

        UUID first = a.claim(TENANT, null).getFirst();
        assertThat(runs.find(first).orElseThrow().window().end()).isEqualTo(fiveHoursAgo);
    }

    @Test
    void aDeploymentThatNeedsReauthorisationGetsNoScheduledRuns() {
        long flow = deployedFlow("stale", wait(0, "stale"), 1, 1);
        schedule(flow, "0 * * * * *", Duration.ofMinutes(1), runs.now().minus(Duration.ofMinutes(1)));
        flows.needsReauthorisation(flow, "invalid_grant");
        RunCoordinator a = coordinator("a", 1);

        a.syncFlow(flow);
        assertThat(a.tick(TENANT)).isZero();
        assertThat(runs.schedule(flow)).as("nothing to make runs from").isEmpty();

        jdbc.update("UPDATE flow SET identity_state = 'ok' WHERE id = ?", flow);
        a.syncFlow(flow);
        assertThat(a.tick(TENANT)).isPositive();
    }

    @Test
    void aRunEndedByARefusedIdentityStopsTheFlowsScheduleAtOnce() {
        long flow = deployedFlow("refused_schedule", wait(0, "refused_schedule"), 1, 3);
        schedule(flow, "* * * * * *", Duration.ofSeconds(1), runs.now().minusSeconds(3));
        RunCoordinator a = coordinator("a", 1, Duration.ZERO, (tenant, flowId) -> {
            flows.needsReauthorisation(flowId, "invalid_grant");
            throw new RunIdentityException("Keycloak refused the flow's identity.", true);
        });
        assertThat(a.tick(TENANT)).isPositive();

        a.claim(TENANT, null);
        await(() -> count("FAILED") == 1);

        await(() -> runs.schedule(flow).isEmpty());
        assertThat(a.tick(TENANT)).as("no more ticks for a deployment nobody can run as").isZero();
    }

    @Test
    void aFlowSwitchedOffOrDeletedMakesNoMoreTicks() {
        long off = deployedFlow("ticking_off", wait(0, "ticking_off"), 1, 1);
        long gone = deployedFlow("ticking_gone", wait(0, "ticking_gone"), 1, 1);
        schedule(off, "* * * * * *", Duration.ofSeconds(1), runs.now().minusSeconds(2));
        schedule(gone, "* * * * * *", Duration.ofSeconds(1), runs.now().minusSeconds(2));
        RunCoordinator a = coordinator("a", 1);
        assertThat(a.tick(TENANT)).isPositive();

        flows.disable(off, "switched off by test");
        a.syncFlow(off);
        runs.purgeFlow(gone);
        jdbc.update("DELETE FROM flow WHERE id = ?", gone);
        sleep(1100);

        assertThat(runs.schedule(off)).isEmpty();
        assertThat(runs.schedule(gone)).isEmpty();
        assertThat(a.tick(TENANT)).isZero();
        assertThat(SharedKvrocks.raw().zrange("flow:sched", 0, -1)).isEmpty();
    }

    @Test
    void theTickerAsksPostgresNothing() {
        long flow = deployedFlow("silent", wait(0, "silent"), 1, 1);
        schedule(flow, "0 * * * * *", Duration.ofMinutes(1),
                runs.now().minus(Duration.ofMinutes(3)).truncatedTo(java.time.temporal.ChronoUnit.MINUTES));
        flows = org.mockito.Mockito.spy(flows);

        assertThat(coordinator("a", 0).tick(TENANT)).isGreaterThanOrEqualTo(3);

        org.mockito.Mockito.verifyNoInteractions(flows);
    }

    @Test
    void aScheduleHoursBehindIsCaughtUpInOnePassAndEveryTickRuns() {
        long flow = deployedFlow("behind", wait(0, "behind"), 8, 1);
        Instant start = runs.now().atZone(ZoneOffset.UTC).withSecond(0).withNano(0).toInstant()
                .minus(Duration.ofHours(2));
        schedule(flow, "0 * * * * *", Duration.ofMinutes(1), start);
        RunCoordinator a = coordinator("a", 8);
        a.startDispatching();

        // Two hours of minutes: a hundred and twenty ticks and the one due now, all made into runs by
        // the first pass, in order, not one pass for each handful.
        assertThat(a.tick(TENANT)).isBetween(121, 122);

        assertThat(nextTickOf(flow)).as("the schedule has caught up with the clock").isAfter(runs.now().minusSeconds(60));
        assertThat(a.tick(TENANT)).isLessThanOrEqualTo(1);
        await(() -> {
            a.sweepAll();
            return count("SUCCEEDED") >= 121;
        });
        assertThat(count("FAILED") + count("CANCELLED")).isZero();
    }

    /**
     * An hour of per-second ticks is thousands of runs to make: more than a pass can in its budget. Each
     * pass makes real progress, the passes together make every second of the hour a run once, and the
     * schedule is current at the end.
     */
    @Test
    void aMuchLargerBacklogIsWorkedOffOverPassesEachMakingProgress() {
        long flow = deployedFlow("hour_behind", wait(0, "hour_behind"), 8, 1);
        Instant start = runs.now().atZone(ZoneOffset.UTC).withNano(0).toInstant().minus(Duration.ofHours(1));
        schedule(flow, "* * * * * *", Duration.ofSeconds(1), start);
        RunCoordinator a = coordinator("a", 0);

        List<Integer> passes = new ArrayList<>();
        do {
            passes.add(a.tick(TENANT));
        } while (Duration.between(nextTickOf(flow), runs.now()).toSeconds() > 2 && passes.size() < 30);

        assertThat(passes.getFirst()).as("a pass makes real progress").isGreaterThan(200);
        assertThat(passes).as("passes").hasSizeLessThan(30);
        int total = passes.stream().mapToInt(Integer::intValue).sum();
        assertThat(total).isBetween(3600, 3610);
        assertThat(windowStartsOfAllRuns()).as("every second of the hour has its run, once")
                .isEqualTo(everySecondFrom(start.minusSeconds(1), total));
        System.out.printf("backlog: an hour of per-second ticks became %d runs in passes of %s%n", total, passes);
    }

    /**
     * Ticks that keep arriving while a backlog is worked off, as they do after downtime: the schedule is
     * caught up, and stays so, with every second of the time covered by a run once.
     */
    @Test
    void aBacklogGrowingWhileItIsWorkedOffStillCatchesUp() {
        long flow = deployedFlow("sustained", wait(0, "sustained"), 8, 1);
        Instant start = runs.now().atZone(ZoneOffset.UTC).withNano(0).toInstant().minus(Duration.ofMinutes(10));
        schedule(flow, "* * * * * *", Duration.ofSeconds(1), start);
        RunCoordinator a = coordinator("a", 8);
        heartbeating.add(a);
        a.startDispatching();

        long peak = Duration.between(nextTickOf(flow), runs.now()).toSeconds();
        boolean caughtUp = false;
        long until = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        while (!caughtUp && System.nanoTime() < until) {
            a.tickAll();
            a.sweepAll();
            long behind = Duration.between(nextTickOf(flow), runs.now()).toSeconds();
            caughtUp = behind < 3 && count("PENDING") + count("RUNNING") <= 10;
            sleep(100);
        }

        assertThat(peak).as("ticks the schedule was behind at first").isGreaterThanOrEqualTo(590);
        assertThat(caughtUp).as("the schedule caught up with the clock and the runs with the schedule").isTrue();
        assertThat(count("SUCCEEDED")).isGreaterThanOrEqualTo(600);
        assertThat(count("FAILED")).isZero();
        List<Instant> windows = windowStartsOfAllRuns();
        assertThat(windows).as("every second since the first tick has its run, once")
                .isEqualTo(everySecondFrom(start.minusSeconds(1), windows.size()));
        assertThat(StoreInvariants.violations()).isEmpty();
    }

    /**
     * Three instances tick, sweep and dispatch at once for a flow with a window of a second that runs
     * every second: each second has one run, made soon after it, and run.
     */
    @Test
    void threeInstancesTickingAtOnceMakeEverySecondOfAOneSecondFlowARunExactlyOnce() throws Exception {
        long flow = deployedFlow("every_second", wait(0, "every_second"), 8, 1);
        Instant start = runs.now().atZone(ZoneOffset.UTC).withNano(0).toInstant().plusSeconds(1);
        schedule(flow, "* * * * * *", Duration.ofSeconds(1), start);
        List<RunCoordinator> instances = List.of(coordinator("a", 4), coordinator("b", 4), coordinator("c", 4));
        instances.forEach(c -> {
            heartbeating.add(c);
            c.startDispatching();
        });

        loop(instances, Duration.ofMillis(200), Duration.ofSeconds(12));

        List<Instant> windows = windowStartsOfAllRuns();
        assertThat(windows).hasSizeGreaterThanOrEqualTo(10);
        assertThat(windows).as("one run for each second, in a row").isEqualTo(everySecondFrom(start.minusSeconds(1), windows.size()));
        assertThat(SharedKvrocks.keys("flow:run:*")).as("and no second made twice").hasSize(windows.size());
        await(() -> count("SUCCEEDED") == windows.size());
        long worstLateness = SharedKvrocks.keys("flow:run:*").stream()
                .mapToLong(key -> Long.parseLong(SharedKvrocks.raw().hget(key, "created_ms"))
                        - Long.parseLong(SharedKvrocks.raw().hget(key, "window_end_ms"))).max().orElseThrow();
        assertThat(worstLateness).as("how long after its tick a run was made, ms").isLessThan(2000);
        assertThat(StoreInvariants.violations()).isEmpty();
    }

    @Test
    void anInstanceThatDiesHoldingAFlowsTicksDelaysThemOnlyUntilItsLockExpiresAndLosesNone() throws Exception {
        long flow = deployedFlow("locked", wait(0, "locked"), 8, 1);
        Instant start = runs.now().atZone(ZoneOffset.UTC).withNano(0).toInstant().plusSeconds(1);
        schedule(flow, "* * * * * *", Duration.ofSeconds(1), start);
        assertThat(runs.lockSchedule(flow, "dead-instance", Duration.ofSeconds(4))).isTrue();
        List<RunCoordinator> instances = List.of(coordinator("b", 4), coordinator("c", 4));
        instances.forEach(c -> {
            heartbeating.add(c);
            c.startDispatching();
        });

        loop(instances, Duration.ofMillis(200), Duration.ofSeconds(2));
        assertThat(SharedKvrocks.keys("flow:run:*")).as("nothing while the dead instance holds it").isEmpty();
        loop(instances, Duration.ofMillis(200), Duration.ofSeconds(7));

        List<Instant> windows = windowStartsOfAllRuns();
        assertThat(windows).hasSizeGreaterThanOrEqualTo(6);
        assertThat(windows).as("the seconds it held are made up for: none is missing")
                .isEqualTo(everySecondFrom(start.minusSeconds(1), windows.size()));
    }

    @Test
    void aRunStoreThatLostItsSchedulesStartsAgainFromTheCheckpoint() {
        long flow = deployedFlow("checkpointed", wait(0, "checkpointed"), 8, 1);
        Instant start = runs.now().atZone(ZoneOffset.UTC).withNano(0).toInstant().minusSeconds(20);
        schedule(flow, "* * * * * *", Duration.ofSeconds(1), start);
        RunCoordinator a = coordinator("a", 0);
        a.tick(TENANT);
        a.checkpoint(TENANT);
        Instant saved = jdbc.queryForObject("SELECT next_tick FROM flow WHERE id = ?", Timestamp.class, flow)
                .toInstant();
        assertThat(saved).as("Postgres has the position").isAfterOrEqualTo(start.plusSeconds(20));

        SharedKvrocks.flush();
        assertThat(runs.schedule(flow)).isEmpty();
        a.sweepAll();

        assertThat(nextTickOf(flow)).as("started again where it was saved").isEqualTo(saved);
        sleep(2100);
        assertThat(a.tick(TENANT)).isGreaterThanOrEqualTo(2);
        List<Instant> windows = windowStartsOfAllRuns();
        assertThat(windows.getFirst()).isEqualTo(saved.minusSeconds(1));
        assertThat(windows).isEqualTo(everySecondFrom(saved.minusSeconds(1), windows.size()));
    }

    @Test
    void aResyncKeepsAScheduleWhereItHasGotToAndARedeploymentStartsItOver() {
        long flow = deployedFlow("redeployed", wait(0, "redeployed"), 8, 1);
        Instant start = runs.now().atZone(ZoneOffset.UTC).withNano(0).toInstant().minusSeconds(30);
        schedule(flow, "* * * * * *", Duration.ofSeconds(1), start);
        RunCoordinator a = coordinator("a", 0);
        a.tick(TENANT);
        Instant reached = nextTickOf(flow);
        assertThat(reached).isAfter(start.plusSeconds(25));

        a.sweepAll();
        assertThat(nextTickOf(flow)).as("Postgres still has the first tick; the run store is ahead of it")
                .isEqualTo(reached);

        Instant again = reached.minusSeconds(10);
        schedule(flow, "* * * * * *", Duration.ofSeconds(1), again);
        assertThat(nextTickOf(flow)).as("a new deployment starts at its first tick").isEqualTo(again);
    }

    /**
     * A backlog is made by one instance, the one that happens to tick, and the others have been idle
     * since their one sweep and are not swept again, as the sweep is ten seconds apart: they still find
     * the runs themselves, and every instance's slots are full while the backlog lasts.
     */
    @Test
    void aBacklogKeepsEveryInstancesSlotsFullAndNoInstanceIsLeftWithoutWork() throws Exception {
        long flow = deployedFlow("busy", wait(150, "busy"), 12, 1);
        List<RunCoordinator> instances = List.of(coordinator("a", 4), coordinator("b", 4), coordinator("c", 4));
        instances.forEach(c -> {
            heartbeating.add(c);
            c.startDispatching();
            c.sweepAll();
        });
        // Nothing is queued yet: every instance looked, found nothing and went to sleep.
        Instant start = runs.now().atZone(ZoneOffset.UTC).withNano(0).toInstant().minus(Duration.ofMinutes(10));
        schedule(flow, "* * * * * *", Duration.ofSeconds(1), start);
        List<int[]> samples = new CopyOnWriteArrayList<>();
        java.util.concurrent.atomic.AtomicBoolean done = new java.util.concurrent.atomic.AtomicBoolean();
        var raw = SharedKvrocks.raw();
        Thread sampler = Thread.ofVirtual().start(() -> {
            while (!done.get()) {
                // Only while there is more waiting than all the slots could take at once.
                if (raw.zcard("flow:q:" + flow) > 24) {
                    samples.add(new int[]{instances.get(0).running(), instances.get(1).running(),
                            instances.get(2).running()});
                }
                sleep(50);
            }
        });

        long until = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        while (count("SUCCEEDED") < 600 && System.nanoTime() < until) {
            instances.getFirst().tickAll();
            sleep(200);
        }
        done.set(true);
        sampler.join();

        assertThat(count("SUCCEEDED")).isGreaterThanOrEqualTo(600);
        assertThat(samples).as("a backlog was there to be worked").hasSizeGreaterThan(20);
        for (int i = 0; i < 3; i++) {
            int instance = i;
            double average = samples.stream().mapToInt(x -> x[instance]).average().orElse(0);
            assertThat(average).as("instance %d: slots in use of 4, while a backlog waits".formatted(instance))
                    .isGreaterThanOrEqualTo(3.0);
        }
    }

    // ---------------------------------------------------------------- fixtures

    /** Gives a deployed flow a schedule in Postgres, as a deployment does, and tells the run store of it. */
    private void schedule(long flow, String cron, Duration window, Instant firstTick) {
        jdbc.update("UPDATE flow SET trigger = ?::jsonb, next_tick = ?, next_due_at = ?, deployed_at = now() "
                        + "WHERE id = ?",
                new Schedule(Schedule.SCHEDULE, cron, ZoneOffset.UTC, window, null).toJson(JSON),
                Timestamp.from(firstTick), Timestamp.from(firstTick), flow);
        coordinator("deployer", 0).syncFlow(flow);
    }

    /** Where the run store has a flow's schedule got to. */
    private Instant nextTickOf(long flow) {
        return runs.schedule(flow).orElseThrow().nextTick();
    }

    /** Each instance ticks and sweeps on a thread of its own every {@code every}, as its own timers would. */
    private void loop(List<RunCoordinator> instances, Duration every, Duration duration) throws InterruptedException {
        long until = System.nanoTime() + duration.toNanos();
        List<Thread> threads = new ArrayList<>();
        for (RunCoordinator c : instances) {
            threads.add(Thread.ofVirtual().start(() -> {
                while (System.nanoTime() < until) {
                    c.tickAll();
                    c.sweepAll();
                    sleep(every.toMillis());
                }
            }));
        }
        for (Thread thread : threads) {
            thread.join();
        }
    }

    /** The window start of every run in the run store, earliest first. */
    private List<Instant> windowStartsOfAllRuns() {
        return SharedKvrocks.keys("flow:run:*").stream()
                .map(key -> Instant.ofEpochMilli(Long.parseLong(SharedKvrocks.raw().hget(key, "window_start_ms"))))
                .sorted().toList();
    }

    private List<UUID> windowRunIds() {
        return SharedKvrocks.keys("flow:run:*").stream()
                .map(key -> UUID.fromString(key.substring("flow:run:".length()))).toList();
    }

    /** {@code count} instants a second apart from {@code first}. */
    private static List<Instant> everySecondFrom(Instant first, int count) {
        List<Instant> out = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            out.add(first.plusSeconds(i));
        }
        return out;
    }

    /** A saved flow, not deployed. */
    private long savedFlow(String externalId, String processors, String connections) {
        String definition = """
                { "schemaVersion": 1, "externalId": "%s", "dataSet": "ds", "name": "%s",
                  "processors": [ %s ], "connections": [ %s ] }
                """.formatted(externalId, externalId, processors, connections);
        return transaction.execute(s -> flows.create(externalId, 7, "ds", externalId, definition,
                "user-1").id());
    }

    private UUID preview(Long flow, Integer version, String flowExternalId, String definition, Long dataSetId) {
        Instant end = Instant.parse("2026-10-12T01:00:00Z");
        UUID id = UUID.randomUUID();
        runs.enqueuePreview(new NewPreview(id, flow, version, flowExternalId, definition, dataSetId,
                Duration.ofMinutes(15), new TimeWindow(end.minus(Duration.ofHours(1)), end), null, "user-1"));
        return id;
    }

    private UUID unsavedPreview(String externalId, String processor) {
        String definition = """
                { "schemaVersion": 1, "externalId": "%s", "dataSet": "ds", "name": "%s",
                  "processors": [ %s ], "connections": [] }
                """.formatted(externalId, externalId, processor);
        return preview(null, null, externalId, definition, 7L);
    }

    /** Three points of series {@code ts_in} inside the preview's window, as a timeseries item. */
    private static String points() {
        return """
                { "id": "p", "type": "test.points" }
                """;
    }

    private static String sink() {
        return """
                { "id": "out", "type": "write-timeseries", "properties": { "target": "ts_out" } }
                """;
    }

    private RunCoordinator coordinator(String name, int maxRunning) {
        return coordinator(name, maxRunning, Duration.ZERO);
    }

    private RunCoordinator coordinator(String name, int maxRunning, Duration backoff) {
        return coordinator(name, maxRunning, backoff, (tenant, runAs) -> () -> "token-of-" + runAs);
    }

    private RunCoordinator coordinator(String name, int maxRunning, Duration backoff,
                                       RunCredentials credentials) {
        List<Processor> processors = List.of(new Wait(), new Fail(), new Call(), new Points(),
                new Hang(), new TimeseriesSink());
        FlowCompiler compiler = new FlowCompiler(new FlowValidator(processors, FlowValidator.Limits.DEFAULTS));
        RunExecution execution = new RunExecution(compiler,
                new RunExecutor(java.time.Clock.systemUTC(), d -> { }), flows,
                credentials, tenant -> Map.of(), "http://127.0.0.1:" + datahub.getAddress().getPort(),
                REQUEST_TIMEOUT, JSON, (tenant, run, attempt, flow, version) -> new RunRecords(run,
                        attempt, flow, version, 100_000, java.time.Clock.systemUTC(), batch -> {
                            if (failPublishing) {
                                return java.util.concurrent.CompletableFuture.failedFuture(
                                        new IllegalStateException("Pulsar is down"));
                            }
                            published.add(batch);
                            return java.util.concurrent.CompletableFuture.completedFuture(null);
                        }), Duration.ofSeconds(5), 64L << 20);
        RunEvents events = new RunEvents(runs, credentials,
                "http://127.0.0.1:" + datahub.getAddress().getPort(), REQUEST_TIMEOUT, 20);
        RunCoordinator c = new RunCoordinator(runs, flows, execution, events, registry, schemaCheck, JSON,
                new RunCoordinator.Settings(LEASE, HEARTBEAT, maxRunning, catchUp,
                backoff, backoff.isZero() ? Duration.ZERO : Duration.ofHours(6), Duration.ofSeconds(1), resync,
                dispatchPoll, Duration.ZERO),
                name, clock);
        coordinators.add(c);
        return c;
    }

    private void claimRepeatedly(RunCoordinator c, List<UUID> claimed) {
        long until = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < until) {
            claimed.addAll(c.claim(TENANT, null));
            if (count("PENDING") == 0) {
                return;
            }
        }
    }

    private long deployedFlow(String externalId, String processor, int maxConcurrent, int maxAttempts) {
        long id = storedFlow(externalId, processor, maxConcurrent, maxAttempts);
        runs.syncFlow(new FlowLimits(id, externalId, 7, true, true, maxConcurrent));
        return id;
    }

    /** A flow deployed in Postgres that the run store has not been told of: the sweep or syncFlow(id) does. */
    private long storedFlow(String externalId, String processor, int maxConcurrent, int maxAttempts) {
        String definition = """
                { "schemaVersion": 1, "externalId": "%s", "dataSet": "ds", "name": "%s",
                  "processors": [ %s ], "connections": [] }
                """.formatted(externalId, externalId, processor);
        long id = transaction.execute(s -> {
            long created = flows.create(externalId, 7, "ds", externalId, definition, "user-1").id();
            flows.deploy(created, 1, true, new Schedule(Schedule.MANUAL, null, null, null, null).toJson(JSON),
                    maxConcurrent, maxAttempts, Duration.ofMinutes(15),
                    FlowRepository.Identity.deployer("user-1"), "user-1", null, null);
            return created;
        });
        return id;
    }

    /** Tells the run store the flow's limits, as the sweep would; the test changes them where it matters. */
    private void describe(long flow, boolean enabled, int maxConcurrent) {
        runs.syncFlow(new FlowLimits(flow, externalIdOf(flow), 7, enabled, true, maxConcurrent));
    }

    private String externalIdOf(long flow) {
        return jdbc.queryForObject("SELECT external_id FROM flow WHERE id = ?", String.class, flow);
    }

    private UUID pending(long flow, int hour) {
        return pending(flow, hour, Duration.ofMinutes(15));
    }

    private UUID pending(long flow, int hour, Duration timeout) {
        Instant end = Instant.parse("2026-10-12T00:00:00Z").plus(Duration.ofHours(hour + 1));
        UUID id = UUID.randomUUID();
        runs.enqueue(new NewRun(id, flow, externalIdOf(flow), 7, 1,
                jdbc.queryForObject("SELECT max_attempts FROM flow WHERE id = ?", Integer.class, flow),
                timeout, new TimeWindow(end.minus(Duration.ofHours(1)), end),
                null, "manual", "user-1", "user-1", null));
        return id;
    }

    /** A run of the window that has failed for good, as an earlier attempt of the flow left it. */
    private UUID failedRun(long flow, int hour) {
        UUID id = pending(flow, hour);
        runs.claimOne(id, "earlier", LEASE, Duration.ofMinutes(1));
        runs.complete(id, "earlier", 1, "FAILED", false, new RunStore.Outcome("PROCESSOR_FAILED", "boom", null, null, "{}"));
        return id;
    }

    private List<String> attemptsOfAllRuns() {
        List<String> out = new ArrayList<>();
        SharedKvrocks.keys("flow:run:*").forEach(key -> out.add(SharedKvrocks.raw().hget(key, "attempt")));
        return out;
    }

    /** The end of every run's window, in the order the runs were made. */
    private List<Instant> windowEndsByCreation() {
        List<String[]> rows = new ArrayList<>();
        for (String key : SharedKvrocks.keys("flow:run:*")) {
            rows.add(new String[]{SharedKvrocks.raw().hget(key, "created_ms"), SharedKvrocks.raw().hget(key, "window_end_ms")});
        }
        rows.sort((x, y) -> {
            int byCreation = Long.compare(Long.parseLong(x[0]), Long.parseLong(y[0]));
            return byCreation != 0 ? byCreation : Long.compare(Long.parseLong(x[1]), Long.parseLong(y[1]));
        });
        return rows.stream().map(r -> Instant.ofEpochMilli(Long.parseLong(r[1]))).toList();
    }

    /** How many runs the run store holds in this state. */
    private int count(String status) {
        int n = 0;
        for (String key : SharedKvrocks.keys("flow:run:*")) {
            if (status.equals(SharedKvrocks.raw().hget(key, "status"))) {
                n++;
            }
        }
        return n;
    }

    private static void await(BooleanSupplier condition) {
        long until = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > until) {
                throw new AssertionError("Condition not met within 15 s");
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        }
    }

    private static String wait(int millis, String key) {
        return """
                { "id": "w", "type": "test.wait", "properties": { "millis": %d, "key": "%s" } }
                """.formatted(millis, key);
    }

    private static String fail() {
        return """
                { "id": "f", "type": "test.fail" }
                """;
    }

    private static String call(int millis) {
        return """
                { "id": "c", "type": "test.call", "properties": { "millis": %d } }
                """.formatted(millis);
    }

    // ---------------------------------------------------------------- processors

    /**
     * Waits {@code millis}, stopping early when the run is stopped; counts runs at once per key. Then
     * makes one item and ends it, so the run has records.
     */
    static final class Wait implements Processor {
        @Override
        public ProcessorDescriptor describe() {
            return ProcessorDescriptor.builder("test.wait", "1.0.0")
                    .property(PropertyDescriptor.builder("millis", PropertyType.NUMBER).defaultValue(0).build())
                    .property(PropertyDescriptor.builder("key", PropertyType.STRING).defaultValue("k").build())
                    .relationship(new Relationship("success", "Never used", Relationship.Unconnected.TERMINATE))
                    .build();
        }

        @Override
        public void onTrigger(ProcessContext context, ProcessSession session) {
            String key = context.property("key").asString();
            int now = RUNNING.computeIfAbsent(key, k -> new AtomicInteger()).incrementAndGet();
            MOST.computeIfAbsent(key, k -> new AtomicInteger()).accumulateAndGet(now, Math::max);
            try {
                long until = System.nanoTime() + context.property("millis").asDouble().longValue() * 1_000_000;
                while (System.nanoTime() < until && !context.isCancelled() && !RELEASED.contains(key)) {
                    sleep(20);
                }
            } finally {
                RUNNING.get(key).decrementAndGet();
            }
            session.transfer(session.create(), "success");
        }
    }

    static final class Fail implements Processor {
        @Override
        public ProcessorDescriptor describe() {
            return ProcessorDescriptor.builder("test.fail", "1.0.0")
                    .relationship(new Relationship("success", "Never used", Relationship.Unconnected.TERMINATE))
                    .build();
        }

        @Override
        public void onTrigger(ProcessContext context, ProcessSession session) {
            throw new ProcessException("Failing on purpose.");
        }
    }

    /** Calls DataHub every 20 ms for {@code millis}, ignoring refusals, until the run is stopped. */
    static final class Call implements Processor {
        @Override
        public ProcessorDescriptor describe() {
            return ProcessorDescriptor.builder("test.call", "1.0.0")
                    .property(PropertyDescriptor.builder("millis", PropertyType.NUMBER).defaultValue(0).build())
                    .relationship(new Relationship("success", "Never used", Relationship.Unconnected.TERMINATE))
                    .build();
        }

        @Override
        public void onTrigger(ProcessContext context, ProcessSession session) {
            long until = System.nanoTime() + (long) (context.property("millis").asDouble() * 1_000_000);
            AtomicLong calls = new AtomicLong();
            while (System.nanoTime() < until && !context.isCancelled()) {
                try {
                    context.datahub().timeseries().getById(1);
                    calls.incrementAndGet();
                } catch (RuntimeException refused) {
                    // the fence, or anything else: keep trying until the run is stopped
                }
                sleep(20);
            }
        }
    }

    /** Works until the run is stopped, and then fails: it did not get its work done. */
    static final class Hang implements Processor {
        @Override
        public ProcessorDescriptor describe() {
            return ProcessorDescriptor.builder("test.hang", "1.0.0")
                    .relationship(new Relationship("success", "Never used", Relationship.Unconnected.TERMINATE))
                    .build();
        }

        @Override
        public void onTrigger(ProcessContext context, ProcessSession session) {
            sleepUnlessStopped(context, 60_000);
            throw new ProcessException("Stopped before finishing.");
        }
    }

    /** One timeseries item of {@code ts_in}: three points inside the run's window. */
    static final class Points implements Processor {
        @Override
        public ProcessorDescriptor describe() {
            return ProcessorDescriptor.builder("test.points", "1.0.0")
                    .relationship(new Relationship("success", "The series", Relationship.Unconnected.REQUIRED))
                    .build();
        }

        @Override
        public void onTrigger(ProcessContext context, ProcessSession session) {
            TimeWindow window = context.window().orElseThrow();
            long start = window.start().toEpochMilli();
            var points = session.newTimeseries(3);
            for (int i = 0; i < 3; i++) {
                points.add(start + i * 60_000L, i);
            }
            var item = session.write(session.create(), points.build());
            item = session.putAllAttributes(item, Map.of("timeseries.externalId", "ts_in",
                    "window.start", window.start().toString(), "window.end", window.end().toString()));
            session.transfer(item, "success");
        }
    }

    private static void sleepUnlessStopped(ProcessContext context, long millis) {
        long until = System.nanoTime() + millis * 1_000_000;
        while (System.nanoTime() < until && !context.isCancelled()) {
            sleep(20);
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
