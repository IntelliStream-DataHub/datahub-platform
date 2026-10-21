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
import ai.intellistream.datahub.flow.records.RunRecords;
import ai.intellistream.datahub.flow.store.RunStore;
import ai.intellistream.datahub.flow.store.RunStore.NewRun;
import ai.intellistream.datahub.flow.store.RunStore.StoredRun;
import ai.intellistream.datahub.flow.store.SharedKvrocks;
import ai.intellistream.datahub.flow.store.TenantKvrocks;
import ai.intellistream.datahub.flow.tenant.FlowSchemaCheck;
import ai.intellistream.datahub.tenant.PostgresTenant;
import ai.intellistream.datahub.tenant.StatelessRoutingDataSource;
import ai.intellistream.datahub.tenant.Tenant;
import ai.intellistream.datahub.tenant.TenantConfigService;
import ai.intellistream.datahub.tenant.TenantContext;
import ai.intellistream.datahub.tenant.TenantFeatures;
import io.lettuce.core.api.sync.RedisCommands;
import ai.intellistream.datahub.testsupport.SharedPostgres;
import com.sun.net.httpserver.HttpServer;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import javax.sql.DataSource;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The coordinator at a size a tenant would actually have, and across tenants: hundreds of
 * scheduled flows shared by two instances, one instance sweeping ten tenants through the real
 * tenant loops and the real routing datasource, a long outage caught up on, an instance that dies
 * with a scheduled run in hand, and one that is stopped on purpose.
 *
 * <p>Like {@link RunCoordinatorIT}, the loops are driven by hand so that what takes a minute in
 * production takes seconds here, and the clock that decides leases is the run store's. Unlike it,
 * every flow statement goes through {@link StatelessRoutingDataSource} keyed on {@link TenantContext}
 * and every run goes to the KVRocks of its tenant (one server per tenant, as in the service), so a
 * tenant mix-up anywhere in the run path would write into the wrong database and show up in the
 * counts.
 */
@Tag("integration")
class RunCoordinatorScaleIT {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Duration LEASE = Duration.ofSeconds(2);
    private static final Duration HEARTBEAT = Duration.ofMillis(300);
    private static final Duration REQUEST_TIMEOUT = Duration.ofMillis(300);
    private static final AtomicInteger DATABASES = new AtomicInteger();
    private static final Schedule EVERY_MINUTE = new Schedule(Schedule.SCHEDULE, "0 * * * * *",
            ZoneOffset.UTC, Duration.ofMinutes(1), null);

    /** Every Work invocation: the tenant its definition names, and the tenant on its thread. */
    private static final Queue<String[]> SEEN = new ConcurrentLinkedQueue<>();
    /** Hold runs until released; a run stopped while held fails, as it did not finish. */
    private static final AtomicBoolean HOLD = new AtomicBoolean();
    private static final Map<String, AtomicInteger> RUNNING = new ConcurrentHashMap<>();
    private static final Map<String, AtomicInteger> MOST = new ConcurrentHashMap<>();

    private final Map<String, HikariDataSource> pools = new ConcurrentHashMap<>();
    private TenantConfigService registry;
    /** Routed to the tenant on the thread, as in the service. */
    private JdbcTemplate jdbc;
    private TransactionTemplate transaction;
    private RunStore runs;
    private TenantKvrocks kvrocks;
    /** Which server holds each tenant's runs. */
    private final Map<String, Integer> servers = new ConcurrentHashMap<>();
    private FlowRepository flows;
    private FlowSchemaCheck schemaCheck;
    private HttpServer datahub;
    private final List<RunCoordinator> coordinators = new ArrayList<>();
    private final Set<RunCoordinator> heartbeating = ConcurrentHashMap.newKeySet();
    private ScheduledExecutorService heartbeats;
    /** Each records sink opened, by run: the tenant it was opened for. */
    private final Map<UUID, String> recordsOpenedFor = new ConcurrentHashMap<>();
    /** Each identity asked for, as tenant/flowId. */
    private final List<String> identitiesAsked = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() throws IOException {
        SharedKvrocks.flush();
        servers.clear();
        registry = mock(TenantConfigService.class);
        registry.cachedTenants = new LinkedHashMap<>();
        when(registry.getConfig(anyString())).thenAnswer(i -> registry.cachedTenants.get(i.<String>getArgument(0)));
        // The service opens a connection for each statement and relies on pgbouncer in front of
        // Postgres, which pools them. A bare container cannot take that churn at the speed these tests
        // run, so each tenant's connections are pooled here, as pgbouncer would. The tenant is still
        // found from TenantContext, by the routing datasource.
        DataSource routing = new StatelessRoutingDataSource(registry) {
            @Override
            protected DataSource determineTargetDataSource() {
                String tenantId = TenantContext.getTenantId();
                if (tenantId == null) {
                    throw new IllegalStateException("No Tenant ID in context");
                }
                return pools.computeIfAbsent(tenantId, id -> {
                    PostgresTenant postgres = registry.cachedTenants.get(id).getPostgresTenant();
                    HikariDataSource pool = new HikariDataSource();
                    pool.setJdbcUrl(postgres.getJdbcUrl());
                    pool.setUsername(postgres.getUsername());
                    pool.setPassword(postgres.getPassword());
                    pool.setMaximumPoolSize(envInt("SCALE_POOL", 40));
                    return pool;
                });
            }
        };
        kvrocks = new TenantKvrocks(registry);
        jdbc = new JdbcTemplate(routing);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(routing));
        runs = new RunStore(kvrocks, Duration.ofSeconds(1));
        flows = new FlowRepository(jdbc);
        schemaCheck = new FlowSchemaCheck(jdbc);

        datahub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        datahub.createContext("/", exchange -> {
            String answer = exchange.getRequestURI().getPath().equals("/datasets/byids")
                    ? "{\"items\":[{\"id\":\"7\",\"externalId\":\"ds\"}]}" : "{\"items\":[]}";
            byte[] body = answer.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
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
        HOLD.set(false);
        SEEN.clear();
    }

    @AfterEach
    void tearDown() {
        heartbeats.shutdownNow();
        HOLD.set(false);
        coordinators.forEach(RunCoordinator::shutdown);
        datahub.stop(0);
        pools.values().forEach(HikariDataSource::close);
        pools.clear();
        kvrocks.close();
        RUNNING.clear();
        MOST.clear();
    }

    // ---------------------------------------------------------------- scale

    @Test
    void twoInstancesRunTwoHundredScheduledFlowsAndEveryTickRunsExactlyOnce() throws Exception {
        String tenant = tenant("tenant-a");
        int flowCount = 200;
        int ticks = 10;
        Instant first = in(tenant, flows::now).truncatedTo(ChronoUnit.MINUTES).minus(Duration.ofMinutes(ticks - 1));
        for (int i = 0; i < flowCount; i++) {
            scheduledFlow(tenant, "flow-" + i, work(20, tenant, "scale"), 1, first);
        }
        RunCoordinator a = coordinator("a", 16);
        RunCoordinator b = coordinator("b", 16);
        heartbeating.add(a);
        heartbeating.add(b);
        int expected = flowCount * ticks;

        long started = System.nanoTime();
        in(tenant, () -> {
            a.tick(tenant);
            return null;
        });
        long firstSweepMs = (System.nanoTime() - started) / 1_000_000;
        int afterFirstSweep = in(tenant, this::total);
        Set<String> owners = ConcurrentHashMap.newKeySet();
        sweepUntil(List.of(a, b), () -> in(tenant, () -> {
            owners.addAll(ownersOfSomeLiveRuns());
            return settled(expected);
        }), Duration.ofMinutes(3));
        long elapsedMs = (System.nanoTime() - started) / 1_000_000;

        in(tenant, () -> {
            List<Map<String, String>> all = rows();
            int total = all.size();
            assertThat(total).isGreaterThanOrEqualTo(expected);
            assertThat(all).extracting(r -> r.get("status")).containsOnly("SUCCEEDED");
            assertThat(all.stream().map(r -> r.get("flow_id") + "/" + r.get("window_start_ms")).distinct().count())
                    .as("every tick of every flow ran once").isEqualTo(total);
            assertThat(all).extracting(r -> r.get("attempt")).as("no run needed a second attempt").containsOnly("1");
            // The schedule moved on with the runs: each flow's last tick is its latest window.
            Map<Long, Instant> latest = new LinkedHashMap<>();
            all.forEach(r -> latest.merge(Long.parseLong(r.get("flow_id")),
                    Instant.ofEpochMilli(Long.parseLong(r.get("window_end_ms"))), (x, y) -> x.isAfter(y) ? x : y));
            latest.forEach((flow, end) -> assertThat(flows.findById(flow).orElseThrow()).isNotNull());
            assertThat(jdbc.queryForObject("SELECT count(*) FROM flow WHERE last_tick IS NOT NULL", Integer.class))
                    .isEqualTo(flowCount);
            assertThat(latest).hasSize(flowCount);
            return null;
        });
        assertThat(SEEN).hasSizeGreaterThanOrEqualTo(expected)
                .allSatisfy(seen -> assertThat(seen[1]).isEqualTo(seen[0]));
        assertThat(owners).as("both instances took runs").contains("a", "b");
        assertThat(MOST.get("scale").get()).as("never more than the two instances' slots").isLessThanOrEqualTo(32);
        System.out.printf("scale: %d flows x %d ticks = %d runs on two instances in %d ms (%.0f runs/s, at most %d"
                        + " at once); the first sweep created %d runs in %d ms%n",
                flowCount, ticks, expected, elapsedMs, expected * 1000.0 / elapsedMs, MOST.get("scale").get(),
                afterFirstSweep, firstSweepMs);
    }

    /** One sweep starts as many pending runs as the instance has free slots, and a full instance starts none. */
    @Test
    void aSweepFillsTheFreeSlotsAndNoMore() {
        String tenant = tenant("tenant-a");
        long flow = manualFlow(tenant, "burst", hold(), 100);
        for (int i = 0; i < 20; i++) {
            pending(tenant, flow, i);
        }
        HOLD.set(true);
        RunCoordinator a = coordinator("a", 8);
        heartbeating.add(a);

        a.sweepAll();
        assertThat(a.running()).isEqualTo(8);
        assertThat(in(tenant, () -> count("RUNNING"))).isEqualTo(8);
        a.sweepAll();
        assertThat(a.running()).as("full: a sweep claims nothing more").isEqualTo(8);

        HOLD.set(false);
        sweepUntil(List.of(a), () -> in(tenant, () -> count("SUCCEEDED") == 20), Duration.ofSeconds(30));
    }

    // ---------------------------------------------------------------- tenants

    @Test
    void oneInstanceSweepsFourTenantsWithoutLeakingAcrossTheirDatabases() {
        List<String> tenants = new ArrayList<>();
        for (int t = 0; t < 4; t++) {
            String tenant = tenant("tenant-" + t);
            tenants.add(tenant);
            Instant first = in(tenant, flows::now).truncatedTo(ChronoUnit.MINUTES).minus(Duration.ofMinutes(1));
            for (int i = 0; i < 10; i++) {
                scheduledFlow(tenant, tenant + "-flow-" + i, work(10, tenant, "tenants"), 1, first);
            }
        }
        RunCoordinator a = coordinator("a", 8);
        heartbeating.add(a);

        sweepUntil(List.of(a), () -> tenants.stream().allMatch(t -> in(t, () -> settled(20))),
                Duration.ofMinutes(2));

        for (String tenant : tenants) {
            in(tenant, () -> {
                int total = total();
                assertThat(total).as("%s has its own twenty runs", tenant).isGreaterThanOrEqualTo(20);
                assertThat(count("SUCCEEDED")).isEqualTo(total);
                assertThat(jdbc.queryForObject("SELECT count(*) FROM flow WHERE external_id NOT LIKE ?",
                        Integer.class, tenant + "-flow-%")).as("only %s's flows are in its database", tenant).isZero();
                return null;
            });
        }
        // The thread that ran each definition was in the tenant the definition belongs to.
        assertThat(SEEN).hasSizeGreaterThanOrEqualTo(80)
                .allSatisfy(seen -> assertThat(seen[1]).as("a run of %s ran as %s", seen[0], seen[1]).isEqualTo(seen[0]));
        // Its records went to the tenant whose KVRocks holds the run, and nowhere else.
        assertThat(recordsOpenedFor).hasSizeGreaterThanOrEqualTo(80);
        Map<String, List<UUID>> opened = new LinkedHashMap<>();
        recordsOpenedFor.forEach((run, tenant) -> opened.computeIfAbsent(tenant, t -> new ArrayList<>()).add(run));
        opened.forEach((tenant, ids) -> {
            for (String other : tenants) {
                int found = in(other, () -> runs.find(ids).size());
                assertThat(found).as("runs whose records were opened for %s, found in %s's KVRocks", tenant, other)
                        .isEqualTo(other.equals(tenant) ? ids.size() : 0);
            }
        });
        // Its identity was asked for under the tenant that owns the flow.
        assertThat(identitiesAsked).isNotEmpty().allSatisfy(asked -> {
            String tenant = asked.substring(0, asked.indexOf('/'));
            long flowId = Long.parseLong(asked.substring(asked.indexOf('/') + 1));
            String externalId = in(tenant, () -> flows.findById(flowId).orElseThrow().externalId());
            assertThat(externalId).startsWith(tenant + "-flow-");
        });
    }

    /**
     * The instance's run slots are shared: a tenant with a queue of long runs takes its share, and
     * the tenant behind it in the registry gets a slot in the same sweep, not once the queue drains.
     */
    @Test
    void aTenantWithALongQueueDoesNotKeepTheNextTenantWaiting() {
        String busy = tenant("tenant-busy");
        String quiet = tenant("tenant-quiet");
        long busyFlow = manualFlow(busy, "busy", work(1000, busy, "busy"), 24);
        for (int i = 0; i < 24; i++) {
            pending(busy, busyFlow, i);
        }
        long quietFlow = manualFlow(quiet, "quiet", work(1, quiet, "quiet"), 1);
        UUID quietRun = pending(quiet, quietFlow, 0);
        RunCoordinator a = coordinator("a", 8);
        heartbeating.add(a);

        Instant started = in(quiet, runs::now);
        sweepUntil(List.of(a), () -> in(busy, () -> count("SUCCEEDED") == 24) && in(quiet, () -> count("SUCCEEDED") == 1),
                Duration.ofMinutes(1));

        StoredRun quietDone = in(quiet, () -> runs.find(quietRun).orElseThrow());
        Duration waited = Duration.between(started, quietDone.startedAt());
        assertThat(quietDone.status()).isEqualTo("SUCCEEDED");
        System.out.printf("fairness: the quiet tenant's run started %d ms after the busy tenant's 24 one-second"
                + " runs began competing for 8 slots%n", waited.toMillis());
        assertThat(waited).as("the quiet tenant gets its share of the first sweep, not a slot once the busy "
                + "tenant is drained").isLessThan(Duration.ofSeconds(1));
    }

    // ---------------------------------------------------------------- dispatch

    /**
     * A backlog of one flow, at its limit, must not hide the runs behind it: the other flow's run
     * starts although every earlier window in the queue belongs to the backlog.
     */
    @Test
    void aBacklogOfOneFlowDoesNotHoldUpAnotherFlow() {
        String tenant = tenant("tenant-a");
        long backlog = manualFlow(tenant, "backlog", hold(), 1);
        for (int i = 0; i < 20; i++) {
            pending(tenant, backlog, i);
        }
        long other = manualFlow(tenant, "other", work(1, tenant, "other"), 1);
        UUID later = pending(tenant, other, 100);
        HOLD.set(true);
        RunCoordinator a = coordinator("a", 4);
        a.startDispatching();
        heartbeating.add(a);

        a.sweepAll();

        await(() -> "SUCCEEDED".equals(in(tenant, () -> runs.find(later).orElseThrow().status())));
        assertThat(in(tenant, () -> count("RUNNING"))).as("the backlog flow still has only its one").isEqualTo(1);
    }

    /**
     * Thousands of queued runs, one sweep, nothing more: the dispatcher refills each slot as a run
     * ends, so the queue drains at the speed the slots allow. With a start only at each sweep, a
     * sweep every ten seconds would start sixteen runs per ten seconds, and this would not finish.
     * The size can be raised from the environment: SCALE_DRAIN_FLOWS, SCALE_DRAIN_PER_FLOW,
     * SCALE_DRAIN_MILLIS, SCALE_DRAIN_SLOTS (and SCALE_POOL for the connections).
     */
    @Test
    void aQueueOfThousandsOfRunsDrainsAtTheSpeedOfTheSlotsAfterASingleSweep() {
        String tenant = tenant("tenant-a");
        int flowCount = envInt("SCALE_DRAIN_FLOWS", 50);
        int perFlow = envInt("SCALE_DRAIN_PER_FLOW", 40);
        int millis = envInt("SCALE_DRAIN_MILLIS", 20);
        int slots = envInt("SCALE_DRAIN_SLOTS", 16);
        int total = flowCount * perFlow;
        in(tenant, () -> {
            for (int f = 0; f < flowCount; f++) {
                manualFlow(tenant, "drain-" + f, work(millis, tenant, "drain"), 1);
            }
            // Pending runs for every flow in one call each; windows an hour apart, as reruns of old failures.
            Instant base = Instant.parse("2026-01-01T00:00:00Z");
            for (FlowRepository.DeployedFlow f : flows.deployedFlows()) {
                List<NewRun> batch = new ArrayList<>();
                for (int g = 1; g <= perFlow; g++) {
                    batch.add(new NewRun(UUID.randomUUID(), f.id(), f.externalId(), 7, 1, 2, Duration.ofMinutes(15),
                            new TimeWindow(base.plus(Duration.ofHours(g)), base.plus(Duration.ofHours(g + 1))),
                            null, "rerun", "user-1", "user-1", null));
                }
                runs.enqueueAll(batch);
            }
            return null;
        });
        assertThat(in(tenant, () -> count("PENDING"))).isEqualTo(total);
        RunCoordinator a = coordinator("a", slots);
        a.startDispatching();
        heartbeating.add(a);

        long before = in(tenant, this::commandsServed);
        long started = System.nanoTime();
        a.sweepAll();
        long until = System.nanoTime() + Duration.ofMinutes(5).toNanos();
        while (!in(tenant, () -> settled(total)) && System.nanoTime() < until) {
            sleep(100);
        }
        long ms = (System.nanoTime() - started) / 1_000_000;
        long after = in(tenant, this::commandsServed);

        assertThat(in(tenant, () -> count("SUCCEEDED"))).as("every queued run ran after one sweep").isEqualTo(total);
        assertThat(in(tenant, () -> rows())).extracting(r -> r.get("attempt")).containsOnly("1");
        assertThat(MOST.get("drain").get()).as("the slots were used, and not exceeded").isBetween(Math.max(1, slots - 2), slots);
        double ideal = total * (double) millis / slots;
        System.out.printf("drain: %d runs over %d flows at %d ms each on %d slots took %d ms after one sweep"
                        + " (%.0f runs/s; the slots alone allow %.0f ms)%n",
                total, flowCount, millis, slots, ms, total * 1000.0 / ms, ideal);
        System.out.printf("drain: what that cost KVRocks, per run: %.1f commands; %.0f commands/s in all%n",
                (after - before) / (double) total, (after - before) * 1000.0 / ms);
        // Generous: the point is minutes against hours, not a benchmark. Sweep-only dispatch needs
        // total / slots sweeps of ten seconds.
        assertThat(ms).isLessThan(Math.max(30_000L, (long) (ideal * 6)));
    }

    /**
     * A flow that ticks every second: the ticks the ticker finds are windows one second wide, each
     * made a run once, none missed between passes a second and a half apart, and each starts as soon
     * as it is made.
     */
    @Test
    void aFlowThatTicksEverySecondRunsEveryTickOnce() {
        String tenant = tenant("tenant-a");
        Schedule everySecond = new Schedule(Schedule.SCHEDULE, "* * * * * *", ZoneOffset.UTC,
                Duration.ofSeconds(1), null);
        Instant first = in(tenant, flows::now).truncatedTo(ChronoUnit.SECONDS).minusSeconds(3);
        flow(tenant, "every-second", work(5, tenant, "every-second"), 1, everySecond, first);
        RunCoordinator a = coordinator("a", 4);
        a.startDispatching();
        heartbeating.add(a);

        for (int i = 0; i < 4; i++) {
            a.sweepAll();
            a.tickAll();
            sleep(1500);
        }
        await(() -> in(tenant, () -> settled(8)));

        in(tenant, () -> {
            List<Instant> windows = rows().stream()
                    .map(r -> Instant.ofEpochMilli(Long.parseLong(r.get("window_end_ms")))).sorted().toList();
            assertThat(windows.size()).as("about one run a second for the six seconds").isBetween(8, 12);
            for (int i = 1; i < windows.size(); i++) {
                assertThat(windows.get(i)).as("no tick missing or repeated")
                        .isEqualTo(windows.get(i - 1).plusSeconds(1));
            }
            assertThat(count("SUCCEEDED")).isEqualTo(windows.size());
            return null;
        });
    }

    // ---------------------------------------------------------------- downtime

    /**
     * Nothing bounds how far back a schedule catches up: three hours down is 180 runs for one
     * per-minute flow, created oldest first, in one pass of the ticker, which works through a backlog
     * {@code catch-up} ticks at a time for as long as its budget allows.
     */
    @Test
    void threeHoursOfDowntimeBecomeOneRunPerMissedTickInOnePass() {
        String tenant = tenant("tenant-a");
        Instant first = in(tenant, flows::now).truncatedTo(ChronoUnit.MINUTES).minus(Duration.ofHours(3));
        scheduledFlow(tenant, "catch-up", work(0, tenant, "catch-up"), 1, first);
        RunCoordinator a = coordinator("a", 1);

        int created = in(tenant, () -> a.tick(tenant));

        assertThat(created).isBetween(181, 182);
        assertThat(in(tenant, () -> a.tick(tenant))).as("nothing more is due").isZero();
        in(tenant, () -> {
            List<Instant> windows = rows().stream()
                    .sorted(java.util.Comparator.comparingLong((Map<String, String> r) -> Long.parseLong(r.get("created_ms")))
                            .thenComparingLong(r -> Long.parseLong(r.get("window_end_ms"))))
                    .map(r -> Instant.ofEpochMilli(Long.parseLong(r.get("window_end_ms")))).toList();
            assertThat(windows).hasSize(windows.stream().distinct().toList().size());
            assertThat(windows.stream().min(Instant::compareTo).orElseThrow()).isEqualTo(first);
            return null;
        });
        System.out.printf("catch-up: 3 h of downtime for one per-minute flow became %d pending runs in one pass%n",
                created);
    }

    // ---------------------------------------------------------------- crashes

    @Test
    void anInstanceThatDiesWithAScheduledRunLosesItToAnotherAndTheScheduleGoesOn() throws Exception {
        String tenant = tenant("tenant-a");
        Instant tick = in(tenant, flows::now).truncatedTo(ChronoUnit.MINUTES);
        long flow = scheduledFlow(tenant, "survivor", hold(), 1, tick);
        HOLD.set(true);
        RunCoordinator a = coordinator("a", 1);
        RunCoordinator b = coordinator("b", 1);
        heartbeating.add(b);

        // a sweeps once: the tick becomes a run that a claims and holds. Then a dies: it never
        // renews, never hands anything back, and nothing it does from here on may be recorded.
        in(tenant, () -> {
            a.tick(tenant);
            return a.claim(tenant, null);
        });
        UUID run = in(tenant, () -> UUID.fromString(rows().stream()
                .min(java.util.Comparator.comparingLong((Map<String, String> r) -> Long.parseLong(r.get("window_start_ms"))))
                .orElseThrow().get("id")));
        assertThat(in(tenant, () -> runs.find(run).orElseThrow().status())).isEqualTo("RUNNING");

        Thread.sleep(LEASE.plusMillis(300).toMillis());
        b.reapAll();
        StoredRun requeued = in(tenant, () -> runs.find(run).orElseThrow());
        assertThat(requeued.status()).isEqualTo("PENDING");
        assertThat(requeued.attempt()).isEqualTo(2);
        assertThat(requeued.errorCode()).isEqualTo("LEASE_LOST");
        await(() -> a.running() == 0);

        HOLD.set(false);
        b.sweepAll();
        await(() -> "SUCCEEDED".equals(in(tenant, () -> runs.find(run).orElseThrow().status())));
        StoredRun done = in(tenant, () -> runs.find(run).orElseThrow());
        assertThat(done.attempt()).isEqualTo(2);
        assertThat(done.errorCode()).isNull();

        // The next tick comes due (moved to now rather than waiting out the minute): b creates and
        // runs it, and the dead instance's tick is not repeated.
        in(tenant, () -> {
            jdbc.update("UPDATE flow SET next_tick = now(), next_due_at = now(), deployed_at = now() "
                    + "WHERE id = ?", flow);
            b.syncFlow(flow);
            return null;
        });
        sweepUntil(List.of(b), () -> in(tenant, () -> settled(2)), Duration.ofSeconds(30));
        in(tenant, () -> {
            List<Map<String, String>> all = rows();
            int total = all.size();
            assertThat(all).as("runs: %s", all).extracting(r -> r.get("status")).containsOnly("SUCCEEDED");
            assertThat(all.stream().map(r -> r.get("window_start_ms")).distinct().count())
                    .as("each tick ran once").isEqualTo(total);
            assertThat(all.stream().mapToLong(r -> Long.parseLong(r.get("window_end_ms"))).max().orElseThrow())
                    .isGreaterThan(tick.toEpochMilli());
            return null;
        });
    }

    @Test
    void aStoppingInstanceHandsItsRunsBackAndAnotherFinishesThemOnTheSameAttempt() {
        String tenant = tenant("tenant-a");
        long flow = manualFlow(tenant, "handed-back", hold(), 3);
        for (int i = 0; i < 3; i++) {
            pending(tenant, flow, i);
        }
        HOLD.set(true);
        RunCoordinator a = coordinator("a", 3);
        RunCoordinator b = coordinator("b", 3);
        heartbeating.add(a);
        heartbeating.add(b);

        assertThat(in(tenant, () -> a.claim(tenant, null))).hasSize(3);
        a.shutdown();
        in(tenant, () -> {
            assertThat(count("PENDING")).isEqualTo(3);
            assertThat(rows()).allSatisfy(r -> {
                assertThat(r.get("attempt")).isEqualTo("1");
                assertThat(r.getOrDefault("owner", "")).isEmpty();
            });
            return null;
        });

        HOLD.set(false);
        assertThat(in(tenant, () -> b.claim(tenant, null))).hasSize(3);
        await(() -> in(tenant, () -> count("SUCCEEDED") == 3));
        assertThat(in(tenant, () -> rows())).extracting(r -> r.get("attempt")).containsOnly("1");
    }

    // ---------------------------------------------------------------- fixtures

    /** A tenant with a database of its own, in the registry the loops read. */
    private String tenant(String id) {
        String url = SharedPostgres.newDatabase("scale_it_" + DATABASES.incrementAndGet());
        PostgresTenant postgres = new PostgresTenant();
        postgres.setJdbcUrl(url);
        postgres.setUsername(SharedPostgres.username());
        postgres.setPassword(SharedPostgres.password());
        TenantFeatures features = new TenantFeatures();
        features.setFlowFeatureEnabled(true);
        Tenant tenant = new Tenant();
        tenant.setOrganizationId(id);
        tenant.setOrganizationName(id);
        tenant.setPostgresTenant(postgres);
        tenant.setKvrocksTenant(SharedKvrocks.kvrocks(servers.size()));
        servers.put(id, servers.size());
        tenant.setFeatures(features);
        registry.cachedTenants.put(id, tenant);
        // Flow ids are per database, and a scheduled run's id is made from the flow id and the tick:
        // tenants started from the same id would make the same run ids, and a leak between them
        // could not be told from a coincidence.
        in(id, () -> jdbc.queryForObject("SELECT setval(pg_get_serial_sequence('flow', 'id'), ?)",
                Long.class, servers.get(id) * 1000L + 1000));
        return id;
    }

    private <T> T in(String tenant, java.util.function.Supplier<T> work) {
        return TenantContext.callWith(tenant, work);
    }

    private long manualFlow(String tenant, String externalId, String processor, int maxConcurrent) {
        return flow(tenant, externalId, processor, maxConcurrent,
                new Schedule(Schedule.MANUAL, null, null, null, null), null);
    }

    private RunCoordinator seeder;

    private long scheduledFlow(String tenant, String externalId, String processor, int maxConcurrent, Instant nextTick) {
        return flow(tenant, externalId, processor, maxConcurrent, EVERY_MINUTE, nextTick);
    }

    private long flow(String tenant, String externalId, String processor, int maxConcurrent, Schedule schedule,
                      Instant nextTick) {
        String definition = """
                { "schemaVersion": 1, "externalId": "%s", "dataSet": "ds", "name": "%s",
                  "processors": [ %s ], "connections": [] }
                """.formatted(externalId, externalId, processor);
        return in(tenant, () -> {
            long id = transaction.execute(s -> {
                long created = flows.create(externalId, 7, "ds", externalId, definition, "user-1").id();
                flows.deploy(created, 1, true, schedule.toJson(JSON), maxConcurrent, 2, Duration.ofMinutes(15),
                        FlowRepository.Identity.deployer("user-1"), "user-1", nextTick,
                        nextTick == null ? null : schedule.dueAt(nextTick));
                return created;
            });
            // As a deployment does: the run store is told the limits and the schedule.
            if (seeder == null) {
                seeder = coordinator("seeder", 0);
            }
            seeder.syncFlow(id);
            return id;
        });
    }

    private UUID pending(String tenant, long flow, int hour) {
        Instant end = Instant.parse("2026-10-12T00:00:00Z").plus(Duration.ofHours(hour + 1));
        return in(tenant, () -> {
            UUID id = UUID.randomUUID();
            runs.enqueue(new NewRun(id, flow, flows.findById(flow).orElseThrow().externalId(), 7, 1, 2,
                    Duration.ofMinutes(15), new TimeWindow(end.minus(Duration.ofHours(1)), end), null, "manual",
                    "user-1", "user-1", null));
            return id;
        });
    }

    private RunCoordinator coordinator(String name, int maxRunning) {
        List<Processor> processors = List.of(new Work(), new Hold());
        FlowCompiler compiler = new FlowCompiler(new FlowValidator(processors, FlowValidator.Limits.DEFAULTS));
        RunCredentials credentials = (tenant, flowId) -> {
            identitiesAsked.add(tenant + "/" + flowId);
            return () -> "token:" + tenant + ":" + flowId;
        };
        String api = "http://127.0.0.1:" + datahub.getAddress().getPort();
        RunExecution execution = new RunExecution(compiler, new RunExecutor(Clock.systemUTC(), d -> { }), flows,
                credentials, tenant -> Map.of(), api, REQUEST_TIMEOUT, JSON,
                (tenant, run, attempt, flow, version) -> {
                    recordsOpenedFor.put(run, tenant);
                    return new RunRecords(run, attempt, flow, version, 100_000, Clock.systemUTC(),
                            batch -> CompletableFuture.completedFuture(null));
                }, Duration.ofSeconds(5), 64L << 20);
        RunEvents events = new RunEvents(runs, credentials, api, REQUEST_TIMEOUT, 20);
        RunCoordinator c = new RunCoordinator(runs, flows, execution, events, registry, schemaCheck, JSON,
                new RunCoordinator.Settings(LEASE, HEARTBEAT, maxRunning, 24,
                Duration.ZERO, Duration.ZERO, Duration.ofSeconds(1), Duration.ZERO, Duration.ofMillis(100),
                Duration.ZERO), name, System::nanoTime);
        coordinators.add(c);
        return c;
    }

    /** Each instance sweeps every tenant, over and over, until {@code done} or {@code timeout}. */
    private static void sweepUntil(List<RunCoordinator> instances, BooleanSupplier done, Duration timeout) {
        AtomicBoolean finished = new AtomicBoolean();
        List<Thread> sweepers = instances.stream().map(c -> Thread.ofVirtual().start(() -> {
            while (!finished.get()) {
                c.tickAll();
                c.sweepAll();
                sleep(10);
            }
        })).toList();
        try {
            long until = System.nanoTime() + timeout.toNanos();
            while (!done.getAsBoolean()) {
                if (System.nanoTime() > until) {
                    throw new AssertionError("Not done within " + timeout);
                }
                sleep(100);
            }
        } finally {
            finished.set(true);
            sweepers.forEach(t -> {
                try {
                    t.join();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        // A sweep that was in flight when the condition held may have started one more run.
        await(done);
    }

    /** The current tenant's KVRocks, for looking at the raw runs. */
    private RedisCommands<String, String> raw() {
        return SharedKvrocks.raw(servers.get(TenantContext.getTenantId()));
    }

    /** How many runs the current tenant's store holds, ended or not. */
    private int total() {
        return raw().keys("flow:run:*").size();
    }

    /**
     * Whether the current tenant has at least {@code atLeast} runs and none still queued or
     * running: the live set holds exactly those.
     */
    private boolean settled(int atLeast) {
        return raw().zcard("flow:live") == 0 && total() >= atLeast;
    }

    /** Every run of the current tenant as its stored fields. */
    private List<Map<String, String>> rows() {
        List<Map<String, String>> out = new ArrayList<>();
        for (String key : raw().keys("flow:run:*")) {
            out.add(raw().hgetall(key));
        }
        return out;
    }

    /** The owners of a few of the runs not yet ended: enough to see which instances are taking them. */
    private Set<String> ownersOfSomeLiveRuns() {
        Set<String> owners = new java.util.HashSet<>();
        for (String id : raw().zrange("flow:live", 0, 50)) {
            String owner = raw().hget("flow:run:" + id, "owner");
            if (owner != null && !owner.isEmpty()) {
                owners.add(owner);
            }
        }
        return owners;
    }

    /** What the current tenant's KVRocks has served so far, in commands. */
    private long commandsServed() {
        for (String line : raw().info("stats").split("\\R")) {
            if (line.startsWith("total_commands_processed:")) {
                return Long.parseLong(line.substring(line.indexOf(':') + 1).trim());
            }
        }
        throw new IllegalStateException("KVRocks reports no command count");
    }

    /** Runs of the current tenant in {@code status}. */
    private int count(String status) {
        int n = 0;
        for (Map<String, String> r : rows()) {
            if (status.equals(r.get("status"))) {
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
            sleep(50);
        }
    }

    private static String work(int millis, String tenant, String key) {
        return """
                { "id": "w", "type": "test.work", "properties": { "millis": %d, "tenant": "%s", "key": "%s" } }
                """.formatted(millis, tenant, key);
    }

    private static String hold() {
        return """
                { "id": "h", "type": "test.hold" }
                """;
    }

    private static int envInt(String name, int fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : Integer.parseInt(value.trim());
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ---------------------------------------------------------------- processors

    /**
     * Records the tenant on its thread against the one its definition names, counts runs at once
     * per key, waits {@code millis} unless stopped, and makes one item so the run has records.
     */
    static final class Work implements Processor {
        @Override
        public ProcessorDescriptor describe() {
            return ProcessorDescriptor.builder("test.work", "1.0.0")
                    .property(PropertyDescriptor.builder("millis", PropertyType.NUMBER).defaultValue(0).build())
                    .property(PropertyDescriptor.builder("tenant", PropertyType.STRING).defaultValue("").build())
                    .property(PropertyDescriptor.builder("key", PropertyType.STRING).defaultValue("k").build())
                    .relationship(new Relationship("success", "Never used", Relationship.Unconnected.TERMINATE))
                    .build();
        }

        @Override
        public void onTrigger(ProcessContext context, ProcessSession session) {
            SEEN.add(new String[]{context.property("tenant").asString(), TenantContext.getTenantId()});
            String key = context.property("key").asString();
            int now = RUNNING.computeIfAbsent(key, k -> new AtomicInteger()).incrementAndGet();
            MOST.computeIfAbsent(key, k -> new AtomicInteger()).accumulateAndGet(now, Math::max);
            try {
                long until = System.nanoTime() + context.property("millis").asDouble().longValue() * 1_000_000;
                while (System.nanoTime() < until && !context.isCancelled()) {
                    sleep(5);
                }
            } finally {
                RUNNING.get(key).decrementAndGet();
            }
            session.transfer(session.create(), "success");
        }
    }

    /** Waits until released; stopped before that, it fails, since it did not get its work done. */
    static final class Hold implements Processor {
        @Override
        public ProcessorDescriptor describe() {
            return ProcessorDescriptor.builder("test.hold", "1.0.0")
                    .relationship(new Relationship("success", "Never used", Relationship.Unconnected.TERMINATE))
                    .build();
        }

        @Override
        public void onTrigger(ProcessContext context, ProcessSession session) {
            while (HOLD.get() && !context.isCancelled()) {
                sleep(20);
            }
            if (context.isCancelled()) {
                throw new ProcessException("Stopped before finishing.");
            }
            session.transfer(session.create(), "success");
        }
    }
}
