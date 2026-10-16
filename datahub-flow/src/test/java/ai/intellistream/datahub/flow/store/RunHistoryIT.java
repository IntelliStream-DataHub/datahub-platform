// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.flow.store;

import ai.intellistream.datahub.clickhouse.ClickHouseClientPool;
import ai.intellistream.datahub.flow.api.TimeWindow;
import ai.intellistream.datahub.flow.store.RunStore.Claimed;
import ai.intellistream.datahub.flow.store.RunStore.FlowLimits;
import ai.intellistream.datahub.flow.store.RunStore.NewPreview;
import ai.intellistream.datahub.flow.store.RunStore.NewRun;
import ai.intellistream.datahub.flow.store.RunStore.Outcome;
import ai.intellistream.datahub.flow.store.RunStore.StoredRun;
import ai.intellistream.datahub.tenant.Tenant;
import ai.intellistream.datahub.tenant.TenantConfigService;
import ai.intellistream.datahub.tenant.TenantContext;
import ai.intellistream.datahub.tenant.TenantFeatures;
import com.clickhouse.client.api.Client;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * A run through its whole life across the two stores: made and ended in KVRocks, archived into
 * ClickHouse, read back from there once KVRocks has let it go. Against the shipped schema file.
 */
@Tag("integration")
class RunHistoryIT {

    private static final String TENANT = "tenant-h";
    private static final Instant BASE = Instant.parse("2026-10-12T00:00:00Z");
    private static final Outcome OUTCOME = new Outcome(null, null, null, null, "{}");

    private static ClickHouseContainer clickhouse;
    private static Client client;

    private TenantKvrocks kvrocks;
    private RunStore store;
    private RunHistory history;
    private RunArchiver archiver;

    @BeforeAll
    static void start() throws Exception {
        clickhouse = new ClickHouseContainer(DockerImageName.parse("clickhouse/clickhouse-server:26.8.2.7"))
                .withUsername("tester").withPassword("test")
                .waitingFor(Wait.forHttp("/ping").forPort(8123).forStatusCode(200));
        clickhouse.start();
        String endpoint = "http://" + clickhouse.getHost() + ":" + clickhouse.getMappedPort(8123);
        try (Client admin = ClickHouseClientPool.transport(new Client.Builder().addEndpoint(endpoint)
                .setUsername("tester").setPassword("test")).build()) {
            admin.query("CREATE DATABASE IF NOT EXISTS run_history_it").get().close();
        }
        client = ClickHouseClientPool.transport(new Client.Builder().addEndpoint(endpoint)
                        .setUsername("tester").setPassword("test").setDefaultDatabase("run_history_it"))
                .serverSetting("async_insert", "0").serverSetting("session_timezone", "UTC").build();
        String schema = Files.readAllLines(Path.of("../datahub-api/src/main/resources/db/clickhouse.sql"))
                .stream().filter(line -> !line.strip().startsWith("--")).collect(Collectors.joining("\n"));
        for (String statement : schema.split(";")) {
            if (statement.contains("flow_run (")) {
                client.query(statement).get().close();
            }
        }
    }

    @AfterAll
    static void stop() {
        if (client != null) {
            client.close();
        }
        if (clickhouse != null) {
            clickhouse.stop();
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        SharedKvrocks.flush();
        client.query("TRUNCATE TABLE flow_run").get().close();
        TenantConfigService tenants = SharedKvrocks.tenants(TENANT);
        TenantFeatures features = new TenantFeatures();
        features.setFlowFeatureEnabled(true);
        Tenant tenant = tenants.getConfig(TENANT);
        tenant.setFeatures(features);
        tenants.cachedTenants = Map.of(TENANT, tenant);
        kvrocks = new TenantKvrocks(tenants);
        store = new RunStore(kvrocks, Duration.ofSeconds(1));
        history = new RunHistory(id -> client, id -> client);
        archiver = new RunArchiver(tenants, store, history);
        TenantContext.setTenantId(TENANT);
        store.syncFlow(new FlowLimits(1, "temp_hourly", 7, true, true, 5));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        kvrocks.close();
    }

    @Test
    void aFinishedRunIsArchivedAndReadBackWhatItWasWhenKvrocksLetsGo() throws Exception {
        NewRun run = run(0, "{\"limit\":35}");
        store.enqueue(run);
        Claimed claimed = store.claim("a", Duration.ofSeconds(30), 1, Duration.ofMinutes(1)).getFirst();
        store.complete(run.id(), "a", claimed.attempt(), "FAILED", true, new Outcome("PROCESSOR_FAILED",
                "boom", "{\"limit\":35}", "{\"clip\":\"1.0.0\"}", "{\"records\":7}"));
        StoredRun ended = store.find(run.id()).orElseThrow();

        assertThat(archiver.archive(TENANT)).isEqualTo(1);
        assertThat(archiver.archive(TENANT)).as("nothing left to archive").isZero();
        Thread.sleep(1500);

        assertThat(store.find(run.id())).as("KVRocks owes the event, so it keeps the run").isPresent();
        StoredRun archived = history.find(TENANT, run.id()).orElseThrow();
        assertThat(archived.status()).isEqualTo("FAILED");
        assertThat(archived.flowId()).isEqualTo(1L);
        assertThat(archived.flowExternalId()).isEqualTo("temp_hourly");
        assertThat(archived.dataSetId()).isEqualTo(7L);
        assertThat(archived.version()).isEqualTo(3);
        assertThat(archived.attempt()).isEqualTo(1);
        assertThat(archived.errorCode()).isEqualTo("PROCESSOR_FAILED");
        assertThat(archived.error()).isEqualTo("boom");
        assertThat(archived.parameters()).isEqualTo("{\"limit\":35}");
        assertThat(archived.processorVersions()).isEqualTo("{\"clip\":\"1.0.0\"}");
        assertThat(archived.stats()).isEqualTo("{\"records\":7}");
        assertThat(archived.window()).isEqualTo(ended.window());
        assertThat(archived.createdAt()).isEqualTo(ended.createdAt());
        assertThat(archived.startedAt()).isEqualTo(ended.startedAt());
        assertThat(archived.finishedAt()).isEqualTo(ended.finishedAt());
        assertThat(archived.trigger()).isEqualTo("schedule");
        assertThat(archived.triggeredBy()).isEqualTo("user-1");
        assertThat(archived.runAs()).isEqualTo("user-1");
        assertThat(archived.preview()).isFalse();
    }

    @Test
    void aRunThatNeverStartedAndHasNoWindowIsReadBackWithoutThem() throws Exception {
        UUID preview = UUID.randomUUID();
        store.enqueuePreview(new NewPreview(preview, null, null, "unsaved", "{}", 7L, Duration.ofMinutes(5),
                null, null, "user-2"));
        store.cancelPending(preview, "CANCELLED", "no");
        archiver.archive(TENANT);

        StoredRun archived = history.find(TENANT, preview).orElseThrow();
        assertThat(archived.flowId()).isNull();
        assertThat(archived.window()).isNull();
        assertThat(archived.startedAt()).isNull();
        assertThat(archived.preview()).isTrue();
        assertThat(archived.flowExternalId()).isEqualTo("unsaved");
    }

    @Test
    void theSameRunsInsertedTwiceAreOneRowToAReader() throws Exception {
        NewRun run = run(0, null);
        store.enqueue(run);
        store.claim("a", Duration.ofSeconds(30), 1, Duration.ofMinutes(1));
        store.complete(run.id(), "a", 1, "SUCCEEDED", false, OUTCOME);
        List<StoredRun> batch = store.takeUnarchived(10, Duration.ofSeconds(30));

        history.insert(TENANT, batch);
        history.insert(TENANT, batch);   // an archiver that died before saying so, and a second one
        history.insert(TENANT, List.of(store.find(run.id()).orElseThrow()));

        assertThat(history.list(TENANT, null, null, false, null, null, 10)).hasSize(1);
    }

    @Test
    void historyListsNewestFirstInPagesNarrowedToDatasetsAndFlowsWithoutPreviews() throws Exception {
        List<UUID> ids = new ArrayList<>();
        for (int hour = 0; hour < 5; hour++) {
            NewRun run = run(hour, null);
            ids.add(run.id());
            store.enqueue(run);
            Thread.sleep(5);
        }
        store.syncFlow(new FlowLimits(2, "other", 8, true, true, 5));
        NewRun elsewhere = new NewRun(UUID.randomUUID(), 2, "other", 8, 1, 2, Duration.ofMinutes(15),
                new TimeWindow(BASE, BASE.plusSeconds(3600)), null, "manual", "user-1", "user-1", null);
        store.enqueue(elsewhere);
        UUID preview = UUID.randomUUID();
        store.enqueuePreview(new NewPreview(preview, 1L, 3, "temp_hourly", null, 7L, Duration.ofMinutes(5),
                new TimeWindow(BASE, BASE.plusSeconds(3600)), null, "user-1"));
        for (Claimed c : store.claim("a", Duration.ofSeconds(30), 10, Duration.ofMinutes(1))) {
            store.complete(c.id(), "a", c.attempt(), "SUCCEEDED", false, OUTCOME);
        }
        store.claimOne(preview, "a", Duration.ofSeconds(30), Duration.ofMinutes(1));
        store.complete(preview, "a", 1, "SUCCEEDED", false, OUTCOME);
        archiver.archive(TENANT);

        List<StoredRun> all = history.list(TENANT, null, null, false, null, null, 100);
        assertThat(all).extracting(StoredRun::id).containsExactlyInAnyOrder(
                ids.get(0), ids.get(1), ids.get(2), ids.get(3), ids.get(4), elsewhere.id());
        assertThat(all).extracting(StoredRun::createdAt).isSortedAccordingTo(java.util.Comparator.reverseOrder());
        assertThat(history.list(TENANT, null, null, true, null, null, 100)).hasSize(7);
        assertThat(history.list(TENANT, List.of(8L), null, false, null, null, 100)).extracting(StoredRun::id)
                .containsExactly(elsewhere.id());
        assertThat(history.list(TENANT, List.of(7L), 1L, false, null, null, 100)).hasSize(5);
        assertThat(history.list(TENANT, List.of(), null, false, null, null, 100)).isEmpty();

        StoredRun third = all.get(2);
        List<StoredRun> after = history.list(TENANT, null, null, false, third.createdAt(), third.id(), 100);
        assertThat(after).extracting(StoredRun::id).containsExactlyElementsOf(
                all.subList(3, all.size()).stream().map(StoredRun::id).toList());
    }

    @Test
    void theFailedRunsAmongSomeIdsAreFoundForRerunning() throws Exception {
        NewRun fails = run(0, null);
        NewRun fine = run(1, null);
        store.enqueueAll(List.of(fails, fine));
        store.claim("a", Duration.ofSeconds(30), 2, Duration.ofMinutes(1));
        store.complete(fails.id(), "a", 1, "FAILED", true, OUTCOME);
        store.complete(fine.id(), "a", 1, "SUCCEEDED", false, OUTCOME);
        archiver.archive(TENANT);

        assertThat(history.failed(TENANT, 1, List.of(fails.id(), fine.id(), UUID.randomUUID())))
                .extracting(StoredRun::id).containsExactly(fails.id());
        assertThat(history.failed(TENANT, 2, List.of(fails.id()))).as("another flow's").isEmpty();
    }

    @Test
    void aBatchTheArchiverCouldNotWriteComesBackLater() throws Exception {
        NewRun run = run(0, null);
        store.enqueue(run);
        store.claim("a", Duration.ofSeconds(30), 1, Duration.ofMinutes(1));
        store.complete(run.id(), "a", 1, "SUCCEEDED", false, OUTCOME);
        RunHistory broken = new RunHistory(id -> client, id -> {
            throw new IllegalStateException("ClickHouse is down");
        });
        RunArchiver failing = new RunArchiver(mock(TenantConfigService.class), store, broken);

        assertThat(failing.archive(TENANT)).isZero();
        assertThat(store.takeUnarchived(10, Duration.ofSeconds(30))).as("hidden for a while").isEmpty();
        assertThat(SharedKvrocks.raw().zcard("flow:unarchived")).as("but not forgotten").isEqualTo(1);
        assertThat(store.find(run.id())).isPresent();
    }

    private static NewRun run(int hour, String parameters) {
        Instant start = BASE.plus(Duration.ofHours(hour));
        return new NewRun(UUID.randomUUID(), 1, "temp_hourly", 7, 3, 2, Duration.ofMinutes(15),
                new TimeWindow(start, start.plus(Duration.ofHours(1))), parameters, "schedule", "user-1", "user-1", null);
    }
}
