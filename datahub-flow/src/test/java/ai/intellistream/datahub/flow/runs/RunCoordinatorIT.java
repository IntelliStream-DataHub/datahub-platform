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
import ai.intellistream.datahub.flow.runs.RunRepository.NewRun;
import ai.intellistream.datahub.flow.runs.RunRepository.StoredRun;
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

    private JdbcTemplate jdbc;
    private TransactionTemplate transaction;
    private RunRepository runs;
    private FlowRepository flows;
    private HttpServer datahub;
    private final List<Long> requestArrivals = new CopyOnWriteArrayList<>();
    /** Every request DataHub received, as "METHOD path", and the Authorization header it carried. */
    private final List<String> requests = new CopyOnWriteArrayList<>();
    private final List<String> bearers = new CopyOnWriteArrayList<>();
    private volatile boolean dataSetExists = true;
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
        runs = new RunRepository(jdbc);
        flows = new FlowRepository(jdbc);

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
                c.heartbeat(TENANT);
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
        Thread ta = Thread.ofVirtual().start(() -> claimRepeatedly(a, claimedByA));
        Thread tb = Thread.ofVirtual().start(() -> claimRepeatedly(b, claimedByB));
        ta.join();
        tb.join();

        Set<UUID> all = new HashSet<>(claimedByA);
        all.addAll(claimedByB);
        assertThat(claimedByA.size() + claimedByB.size()).isEqualTo(30).isEqualTo(all.size());
        assertThat(claimedByA).isNotEmpty();
        assertThat(claimedByB).isNotEmpty();
        await(() -> count("SUCCEEDED") == 30);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM flow.run WHERE attempt <> 1", Integer.class))
                .isZero();
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
            Thread ta = Thread.ofVirtual().start(() -> a.claim(TENANT, null));
            Thread tb = Thread.ofVirtual().start(() -> b.claim(TENANT, null));
            ta.join();
            tb.join();
            Thread.sleep(50);
        }

        assertThat(count("SUCCEEDED")).isEqualTo(6);
        assertThat(MOST.get("limited").get()).isEqualTo(2);
    }

    @Test
    void missedTicksAreCreatedOldestFirstAndClaimedEarliestWindowFirst() {
        long flow = deployedFlow("hourly", wait(0, "hourly"), 1, 1);
        Instant now = runs.now();
        Instant fiveHoursAgo = now.minus(Duration.ofHours(5)).atZone(ZoneOffset.UTC)
                .withMinute(0).withSecond(0).withNano(0).toInstant();
        jdbc.update("UPDATE flow.flow SET trigger = ?::jsonb, next_tick = ?, next_due_at = ? WHERE id = ?",
                new Schedule(Schedule.SCHEDULE, "0 0 * * * *", ZoneOffset.UTC, Duration.ofHours(1), null)
                        .toJson(JSON),
                Timestamp.from(fiveHoursAgo), Timestamp.from(fiveHoursAgo), flow);
        RunCoordinator a = coordinator("a", 1);

        assertThat(a.createScheduledRuns()).isEqualTo(6);
        assertThat(a.createScheduledRuns()).isZero();
        List<Timestamp> windows = jdbc.queryForList(
                "SELECT window_end FROM flow.run ORDER BY created_at, window_end", Timestamp.class);
        assertThat(windows).hasSize(6).isSorted();
        assertThat(windows.getFirst().toInstant()).isEqualTo(fiveHoursAgo);
        assertThat(jdbc.queryForObject("SELECT last_tick FROM flow.flow WHERE id = ?", Timestamp.class, flow)
                .toInstant()).isEqualTo(windows.getLast().toInstant());

        UUID first = a.claim(TENANT, null).getFirst();
        assertThat(runs.find(first).orElseThrow().window().end()).isEqualTo(fiveHoursAgo);
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

        setWait(flow, 0);
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
        transaction.execute(s -> runs.claim(run, "owner-1", LEASE));
        RunRepository.Outcome outcome = new RunRepository.Outcome(null, null, null, null, "{}");

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
        RunCoordinator a = coordinator("a", 1, Duration.ofHours(1));

        a.claim(TENANT, null);
        await(() -> "PENDING".equals(runs.find(run).orElseThrow().status()));
        StoredRun retried = runs.find(run).orElseThrow();
        assertThat(retried.attempt()).isEqualTo(2);
        assertThat(retried.errorCode()).isEqualTo("PROCESSOR_FAILED");
        assertThat(retried.notBefore()).isAfter(runs.now().plus(Duration.ofMinutes(59)));
        assertThat(a.claim(TENANT, null)).isEmpty();

        jdbc.update("UPDATE flow.run SET not_before = now() WHERE id = ?", run);
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
        UUID failed = pending(flow, 0);
        jdbc.update("UPDATE flow.run SET status = 'FAILED', finished_at = now() WHERE id = ?", failed);
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
        UUID failed = pending(flow, 0);
        jdbc.update("UPDATE flow.run SET status = 'FAILED', finished_at = now() WHERE id = ?", failed);
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
        assertThat(runs.cancelIfPending(waiting, "user-1")).isTrue();
        assertThat(runs.find(waiting).orElseThrow().status()).isEqualTo("CANCELLED");

        assertThat(runs.requestCancel(running)).isTrue();
        await(() -> "CANCELLED".equals(runs.find(running).orElseThrow().status()));
        assertThat(runs.find(running).orElseThrow().attempt()).isEqualTo(1);
    }

    @Test
    void aRunPastItsTimeoutFailsWithTimeout() {
        long flow = deployedFlow("slow", wait(10_000, "slow"), 1, 1);
        jdbc.update("UPDATE flow.flow SET run_timeout = interval '1 second' WHERE id = ?", flow);
        UUID run = pending(flow, 0);
        jdbc.update("UPDATE flow.run SET run_timeout = interval '1 second' WHERE id = ?", run);
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
        jdbc.update("UPDATE flow.flow SET enabled = false WHERE id = ?", flow);

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
        assertThat(jdbc.queryForObject("SELECT disabled_reason FROM flow.flow WHERE id = ?", String.class,
                flow)).isEqualTo(RunCoordinator.DATASET_DELETED);
        assertThat(jdbc.queryForObject("SELECT enabled FROM flow.flow WHERE id = ?", Boolean.class, flow))
                .isFalse();
    }

    @Test
    void aDeploymentThatNeedsReauthorisationGetsNoScheduledRuns() {
        long flow = deployedFlow("stale", wait(0, "stale"), 1, 1);
        jdbc.update("UPDATE flow.flow SET trigger = '{\"type\":\"schedule\",\"cron\":\"0 * * * * *\","
                + "\"timezone\":\"UTC\",\"window\":\"PT1M\"}'::jsonb, "
                + "next_tick = now() - interval '1 minute', next_due_at = now() - interval '1 minute' "
                + "WHERE id = ?", flow);
        flows.needsReauthorisation(flow, "invalid_grant");

        assertThat(coordinator("a", 1).createScheduledRuns()).isZero();
        jdbc.update("UPDATE flow.flow SET identity_state = 'ok' WHERE id = ?", flow);
        assertThat(coordinator("b", 1).createScheduledRuns()).isPositive();
    }

    // ---------------------------------------------------------------- previews

    @Test
    void aPreviewRunsOnlyWhereItWasStartedAsItsCallerAndWritesNothing() {
        // Saved, never deployed: a preview tries a flow out whether or not it is switched on.
        long flow = savedFlow("previewed", points() + "," + sink(),
                "{ \"from\": \"p\", \"relationship\": \"success\", \"to\": \"out\" }");
        UUID run = preview(flow, 1, null, null);
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
    void aPreviewOfAnUnsavedDefinitionIsSeenThroughItsOwnDataset() {
        UUID run = unsavedPreview("unsaved_seen", wait(0, "unsaved_seen"));
        long saved = savedFlow("saved_flow", wait(0, "saved_flow"), "");
        UUID real = pending(saved, 0);

        StoredRun stored = runs.find(run).orElseThrow();
        assertThat(stored.flowId()).isNull();
        assertThat(stored.dataSetId()).isEqualTo(7);
        assertThat(stored.flowExternalId()).isEqualTo("unsaved_seen");

        Caller.DataSets seven = new Caller.DataSets(false, Set.of(7L));
        assertThat(runs.list(seven, null, true, null, null, 10)).extracting(StoredRun::id)
                .containsExactlyInAnyOrder(run, real);
        // Previews are shown apart: only on request.
        assertThat(runs.list(seven, null, false, null, null, 10)).extracting(StoredRun::id)
                .containsExactly(real);
        assertThat(runs.list(new Caller.DataSets(false, Set.of(8L)), null, true, null, null, 10))
                .isEmpty();
    }

    @Test
    void theBoardShowsEachDeployedFlowWithTheRunsItHasInHandAndItsLatestFinishedRun() {
        long busy = deployedFlow("busy", wait(0, "busy"), 2, 1);
        UUID going = pending(busy, 0);
        UUID waiting = pending(busy, 1);
        UUID retrying = pending(busy, 2);
        jdbc.update("UPDATE flow.run SET attempt = 2 WHERE id = ?", retrying);
        transaction.execute(s -> runs.claim(going, "instance-a", LEASE));
        long failing = deployedFlow("failing", wait(0, "failing"), 1, 1);
        UUID failed = pending(failing, 0);
        jdbc.update("UPDATE flow.run SET status = 'FAILED', error_code = 'PROCESSOR_FAILED', "
                + "finished_at = now() WHERE id = ?", failed);
        UUID later = pending(failing, 1);
        jdbc.update("UPDATE flow.run SET status = 'SUCCEEDED', finished_at = now() - interval '1 hour' "
                + "WHERE id = ?", later);
        savedFlow("never_deployed", wait(0, "never_deployed"), "");
        unsavedPreview("board_preview", wait(0, "board_preview"));
        transaction.execute(s -> runs.claim(preview(failing, 1, null, null), "instance-b", LEASE));

        List<RunRepository.BoardRow> board = runs.board(Caller.DataSets.ALL, null, 10);

        // The flow that was never deployed is not on it, and a preview is nobody's run.
        assertThat(board).extracting(RunRepository.BoardRow::externalId).containsExactly("busy", "failing");
        RunRepository.BoardRow b = board.get(0);
        assertThat(b.running()).isEqualTo(1);
        assertThat(b.runningOn()).isEqualTo("instance-a");
        assertThat(b.queued()).isEqualTo(2);
        assertThat(b.retrying()).isEqualTo(1);
        assertThat(b.oldestWaiting()).isNotNull();
        assertThat(b.lastStatus()).isNull();
        RunRepository.BoardRow f = board.get(1);
        assertThat(f.running()).isZero();
        assertThat(f.queued()).isZero();
        // The latest run by creation, not by when it finished.
        assertThat(f.lastRunId()).isEqualTo(later);
        assertThat(f.lastStatus()).isEqualTo("SUCCEEDED");

        // Narrowed to datasets the caller may read, and paged by external id.
        assertThat(runs.board(new Caller.DataSets(false, Set.of(8L)), null, 10)).isEmpty();
        assertThat(runs.board(Caller.DataSets.ALL, "busy", 10)).extracting(RunRepository.BoardRow::externalId)
                .containsExactly("failing");
    }

    @Test
    void failedRunsAreRequeuedOnceEachAndOnlyWhileTheirWindowStillEndsInFailure() {
        long flow = deployedFlow("requeue", wait(0, "requeue"), 1, 2);
        UUID plain = failedRun(flow, 0);
        UUID redone = failedRun(flow, 1);
        UUID healed = failedRun(flow, 2);
        UUID cancelled = failedRun(flow, 3);
        UUID other = failedRun(deployedFlow("other_flow", wait(0, "other_flow"), 1, 2), 0);
        // A later run for the window: waiting, and one that succeeded. And a person's cancel.
        jdbc.update("INSERT INTO flow.run (id, flow_id, version, status, attempt, max_attempts, run_timeout, "
                + "window_start, window_end, trigger) SELECT gen_random_uuid(), flow_id, version, 'PENDING', 1, 2, "
                + "run_timeout, window_start, window_end, 'rerun' FROM flow.run WHERE id = ?", redone);
        jdbc.update("INSERT INTO flow.run (id, flow_id, version, status, attempt, max_attempts, run_timeout, "
                + "window_start, window_end, trigger, finished_at) SELECT gen_random_uuid(), flow_id, version, "
                + "'SUCCEEDED', 1, 2, run_timeout, window_start, window_end, 'rerun', now() FROM flow.run "
                + "WHERE id = ?", healed);
        jdbc.update("UPDATE flow.run SET status = 'CANCELLED' WHERE id = ?", cancelled);
        List<UUID> asked = List.of(plain, redone, healed, cancelled, other);

        int queued = runs.requeueFailed(flow, 1, 2, Duration.ofMinutes(15), "user-2", "user-2", asked);

        // Only the plain failure of this flow: the others were redone, healed, cancelled, or are another flow's.
        assertThat(queued).isEqualTo(1);
        StoredRun again = jdbc.query("SELECT id FROM flow.run WHERE flow_id = ? AND trigger = 'rerun' AND triggered_by = 'user-2'",
                (rs, n) -> runs.find(rs.getObject("id", UUID.class)).orElseThrow(), flow).getFirst();
        assertThat(again.status()).isEqualTo("PENDING");
        assertThat(again.window()).isEqualTo(runs.find(plain).orElseThrow().window());
        assertThat(again.runAs()).isEqualTo("user-2");
        // Asking again queues nothing: the window now has a later run.
        assertThat(runs.requeueFailed(flow, 1, 2, Duration.ofMinutes(15), "user-2", "user-2", asked)).isZero();
    }

    private UUID failedRun(long flow, int hour) {
        UUID id = pending(flow, hour);
        jdbc.update("UPDATE flow.run SET status = 'FAILED', error_code = 'PROCESSOR_FAILED', finished_at = now() "
                + "WHERE id = ?", id);
        return id;
    }

    // ---------------------------------------------------------------- fixtures

    /** A saved flow, not deployed. */
    private long savedFlow(String externalId, String processors, String connections) {
        String definition = """
                { "schemaVersion": 1, "externalId": "%s", "dataSet": "ds", "name": "%s",
                  "processors": [ %s ], "connections": [ %s ] }
                """.formatted(externalId, externalId, processors, connections);
        return transaction.execute(s -> flows.create(externalId, 7, "ds", externalId, definition,
                "user-1").id());
    }

    private UUID preview(Long flow, Integer version, String definition, Long dataSetId) {
        Instant end = Instant.parse("2026-10-12T01:00:00Z");
        return transaction.execute(s -> runs.insertPreview(new RunRepository.NewPreview(flow, version,
                definition, dataSetId, Duration.ofMinutes(15),
                new TimeWindow(end.minus(Duration.ofHours(1)), end), null, "user-1")));
    }

    private UUID unsavedPreview(String externalId, String processor) {
        String definition = """
                { "schemaVersion": 1, "externalId": "%s", "dataSet": "ds", "name": "%s",
                  "processors": [ %s ], "connections": [] }
                """.formatted(externalId, externalId, processor);
        return preview(null, null, definition, 7L);
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
                new RunExecutor(java.time.Clock.systemUTC(), d -> { }), flows, jdbc,
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
        RunCoordinator c = new RunCoordinator(runs, execution, events, transaction, null, null, JSON,
                new RunCoordinator.Settings(LEASE, HEARTBEAT, maxRunning, 24,
                backoff, backoff.isZero() ? Duration.ZERO : Duration.ofHours(6), Duration.ofSeconds(1)),
                name, System::nanoTime);
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
        String definition = """
                { "schemaVersion": 1, "externalId": "%s", "dataSet": "ds", "name": "%s",
                  "processors": [ %s ], "connections": [] }
                """.formatted(externalId, externalId, processor);
        return transaction.execute(s -> {
            long id = flows.create(externalId, 7, "ds", externalId, definition, "user-1").id();
            flows.deploy(id, 1, true, new Schedule(Schedule.MANUAL, null, null, null, null).toJson(JSON),
                    maxConcurrent, maxAttempts, Duration.ofMinutes(15),
                    FlowRepository.Identity.deployer("user-1"), "user-1", null, null);
            return id;
        });
    }

    /** Makes the flow's pending runs wait {@code millis} instead, by pointing them at a new version. */
    private void setWait(long flow, int millis) {
        String externalId = jdbc.queryForObject("SELECT external_id FROM flow.flow WHERE id = ?", String.class, flow);
        String definition = """
                { "schemaVersion": 1, "externalId": "%s", "dataSet": "ds", "name": "%s",
                  "processors": [ %s ], "connections": [] }
                """.formatted(externalId, externalId, wait(millis, externalId));
        transaction.executeWithoutResult(s -> {
            flows.addVersion(flow, 1, 7, "ds", externalId, definition, "user-1").orElseThrow();
            jdbc.update("UPDATE flow.run SET version = 2 WHERE flow_id = ?", flow);
        });
    }

    private UUID pending(long flow, int hour) {
        Instant end = Instant.parse("2026-10-12T00:00:00Z").plus(Duration.ofHours(hour + 1));
        return transaction.execute(s -> runs.insert(new NewRun(flow, 1,
                jdbc.queryForObject("SELECT max_attempts FROM flow.flow WHERE id = ?", Integer.class, flow),
                Duration.ofMinutes(15), new TimeWindow(end.minus(Duration.ofHours(1)), end),
                null, "manual", "user-1", "user-1")));
    }

    private int count(String status) {
        return jdbc.queryForObject("SELECT count(*) FROM flow.run WHERE status = ?", Integer.class, status);
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
                sleepUnlessStopped(context, context.property("millis").asDouble().longValue());
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
