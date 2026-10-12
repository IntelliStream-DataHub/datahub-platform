// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.flow.runs;

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
import ai.intellistream.datahub.flow.runs.RunCredentials.RunIdentityException;
import ai.intellistream.datahub.flow.runs.RunRepository.NewRun;
import ai.intellistream.datahub.flow.runs.RunRepository.StoredRun;
import ai.intellistream.datahub.testsupport.SharedPostgres;
import com.sun.net.httpserver.HttpServer;
import org.apache.arrow.memory.RootAllocator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
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
    private RootAllocator allocator;
    private HttpServer datahub;
    private final List<Long> requestArrivals = new CopyOnWriteArrayList<>();
    private final List<RunCoordinator> coordinators = new ArrayList<>();
    private final Set<RunCoordinator> heartbeating = ConcurrentHashMap.newKeySet();
    private ScheduledExecutorService heartbeats;

    @BeforeEach
    void setUp() throws IOException {
        DriverManagerDataSource ds = new DriverManagerDataSource(
                SharedPostgres.newDatabase("run_coordinator_it_" + DATABASES.incrementAndGet()),
                SharedPostgres.username(), SharedPostgres.password());
        jdbc = new JdbcTemplate(ds);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(ds));
        runs = new RunRepository(jdbc);
        flows = new FlowRepository(jdbc);
        allocator = new RootAllocator(Long.MAX_VALUE);

        datahub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        datahub.createContext("/", exchange -> {
            requestArrivals.add(System.nanoTime());
            byte[] body = "{\"items\":[]}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
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
        assertThat(allocator.getAllocatedMemory()).isZero();
        allocator.close();
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
        assertThat(failed.failureEventOwed()).isTrue();
        assertThat(failed.finishedAt()).isNotNull();
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

    // ---------------------------------------------------------------- fixtures

    private RunCoordinator coordinator(String name, int maxRunning) {
        return coordinator(name, maxRunning, Duration.ZERO);
    }

    private RunCoordinator coordinator(String name, int maxRunning, Duration backoff) {
        return coordinator(name, maxRunning, backoff, (tenant, runAs) -> () -> "token-of-" + runAs);
    }

    private RunCoordinator coordinator(String name, int maxRunning, Duration backoff,
                                       RunCredentials credentials) {
        List<Processor> processors = List.of(new Wait(), new Fail(), new Call());
        FlowCompiler compiler = new FlowCompiler(new FlowValidator(processors, FlowValidator.Limits.DEFAULTS));
        RunExecution execution = new RunExecution(compiler,
                new RunExecutor(allocator, java.time.Clock.systemUTC(), d -> { }), flows, jdbc,
                credentials, tenant -> Map.of(), "http://127.0.0.1:" + datahub.getAddress().getPort(),
                REQUEST_TIMEOUT, JSON);
        RunCoordinator c = new RunCoordinator(runs, execution, transaction, null, null, JSON,
                Long.MAX_VALUE, new RunCoordinator.Settings(LEASE, HEARTBEAT, maxRunning, 4, 24,
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
                    maxConcurrent, maxAttempts, Duration.ofMinutes(15), 1 << 20, "user-1", null, null);
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
                1 << 20, Duration.ofMinutes(15), new TimeWindow(end.minus(Duration.ofHours(1)), end),
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

    /** Waits {@code millis}, stopping early when the run is stopped; counts runs at once per key. */
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
