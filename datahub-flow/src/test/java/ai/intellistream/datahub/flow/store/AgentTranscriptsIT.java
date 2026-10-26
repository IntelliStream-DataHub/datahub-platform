// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.flow.store;

import ai.intellistream.datahub.clickhouse.ClickHouseClientPool;
import ai.intellistream.datahub.flow.api.TimeWindow;
import ai.intellistream.datahub.flow.store.AgentTranscripts.Row;
import ai.intellistream.datahub.flow.store.RunStore.Claimed;
import ai.intellistream.datahub.flow.store.RunStore.FlowLimits;
import ai.intellistream.datahub.flow.store.RunStore.NewRun;
import ai.intellistream.datahub.flow.store.RunStore.Outcome;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An agent run's transcript through its whole life: appended row by row in KVRocks, read from any
 * position, copied to ClickHouse with the run by the archiver, read back from there, and gone from
 * KVRocks with the run's hash. Against the shipped schema file.
 */
@Tag("integration")
class AgentTranscriptsIT {

    private static final String TENANT = "tenant-tr";
    private static final Instant BASE = Instant.parse("2026-10-12T00:00:00Z");
    private static final Outcome OUTCOME = new Outcome(null, null, null, null, "{}");

    private static ClickHouseContainer clickhouse;
    private static Client client;

    private TenantKvrocks kvrocks;
    private RunStore store;
    private RunHistory history;
    private AgentTranscripts transcripts;
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
            admin.query("CREATE DATABASE IF NOT EXISTS transcripts_it").get().close();
        }
        client = ClickHouseClientPool.transport(new Client.Builder().addEndpoint(endpoint)
                        .setUsername("tester").setPassword("test").setDefaultDatabase("transcripts_it"))
                .serverSetting("async_insert", "0").serverSetting("session_timezone", "UTC").build();
        String schema = Files.readAllLines(Path.of("../datahub-api/src/main/resources/db/clickhouse.sql"))
                .stream().filter(line -> !line.strip().startsWith("--")).collect(Collectors.joining("\n"));
        for (String statement : schema.split(";")) {
            if (statement.contains("flow_run (") || statement.contains("flow_run_transcript (")) {
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
        client.query("TRUNCATE TABLE flow_run_transcript").get().close();
        TenantConfigService tenants = SharedKvrocks.tenants(TENANT);
        TenantFeatures features = new TenantFeatures();
        features.setFlowFeatureEnabled(true);
        Tenant tenant = tenants.getConfig(TENANT);
        tenant.setFeatures(features);
        tenants.cachedTenants = Map.of(TENANT, tenant);
        kvrocks = new TenantKvrocks(tenants);
        store = new RunStore(kvrocks, Duration.ofSeconds(1));
        history = new RunHistory(id -> client, id -> client);
        transcripts = new AgentTranscripts(kvrocks);
        archiver = new RunArchiver(tenants, store, history, transcripts);
        TenantContext.setTenantId(TENANT);
        store.syncFlow(new FlowLimits(1, "pump_watch", 7, true, true, 5));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        kvrocks.close();
    }

    @Test
    void rowsAreAppendedInOrderAndReadFromAnyPosition() {
        UUID run = UUID.randomUUID();
        assertThat(transcripts.append(run, 1, BASE, "system", Map.of("text", "You are an agent."))).isPositive();
        transcripts.append(run, 1, BASE.plusSeconds(1), "tool_call", Map.of("name", "event_get", "mutating", false));
        transcripts.append(run, 1, BASE.plusSeconds(2), "note", Map.of("text", "done"));
        transcripts.append(run, 2, BASE.plusSeconds(3), "note", Map.of("text", "second attempt"));

        List<Row> all = transcripts.read(run, 1, -1);
        assertThat(all).extracting(Row::seq).containsExactly(0L, 1L, 2L);
        assertThat(all).extracting(Row::kind).containsExactly("system", "tool_call", "note");
        assertThat(all.get(1).payload().get("mutating").asBoolean()).isFalse();
        assertThat(all.get(1).time()).isEqualTo(BASE.plusSeconds(1));
        assertThat(transcripts.read(run, 1, 0)).extracting(Row::seq).containsExactly(1L, 2L);
        assertThat(transcripts.read(run, 1, 5)).isEmpty();
        assertThat(transcripts.read(run, 2, -1)).extracting(Row::kind).containsExactly("note");
        assertThat(transcripts.read(UUID.randomUUID(), 1, -1)).isEmpty();
    }

    @Test
    void aFinishedRunsTranscriptsGoToClickHouseWithItAndLeaveKvrocksWithItsHash() throws Exception {
        NewRun run = run(0);
        store.enqueue(run);
        Claimed first = store.claim("a", Duration.ofSeconds(30), 1, Duration.ofMinutes(1)).getFirst();
        transcripts.append(run.id(), 1, BASE, "tool_call", Map.of("name", "event_create", "mutating", true));
        store.retry(run.id(), "a", first.attempt(), Duration.ZERO, OUTCOME);
        Claimed second = store.claim("a", Duration.ofSeconds(30), 1, Duration.ofMinutes(1)).getFirst();
        assertThat(second.attempt()).isEqualTo(2);
        transcripts.append(run.id(), 2, BASE.plusSeconds(60), "note", Map.of("text", "Not retried"));
        store.complete(run.id(), "a", 2, "FAILED", false, OUTCOME);

        assertThat(archiver.archive(TENANT)).isEqualTo(1);

        assertThat(history.transcript(TENANT, run.id(), 1, -1)).singleElement().satisfies(row -> {
            assertThat(row.seq()).isZero();
            assertThat(row.kind()).isEqualTo("tool_call");
            assertThat(row.time()).isEqualTo(BASE);
            assertThat(row.payload().get("mutating").asBoolean()).isTrue();
        });
        assertThat(history.transcript(TENANT, run.id(), 2, -1)).extracting(Row::kind).containsExactly("note");
        assertThat(history.transcript(TENANT, run.id(), 2, 0)).isEmpty();
        // Archived twice, one row each to a reader.
        history.insertTranscript(TENANT, run.id(), 1, transcripts.read(run.id(), 1, -1));
        assertThat(history.transcript(TENANT, run.id(), 1, -1)).hasSize(1);

        assertThat(SharedKvrocks.keys("flow:transcript:*")).as("readable for the finished TTL").hasSize(2);
        Thread.sleep(1500);
        assertThat(store.find(run.id())).isEmpty();
        assertThat(SharedKvrocks.keys("flow:transcript:*")).as("gone with the run's hash").isEmpty();
    }

    @Test
    void aPurgedFlowTakesItsRunsTranscriptsWithIt() {
        NewRun run = run(0);
        store.enqueue(run);
        store.claim("a", Duration.ofSeconds(30), 1, Duration.ofMinutes(1));
        transcripts.append(run.id(), 1, BASE, "note", Map.of("text", "running"));

        assertThat(store.purgeFlow(1)).isEqualTo(1);

        assertThat(SharedKvrocks.keys("flow:transcript:*")).isEmpty();
    }

    private static NewRun run(int hour) {
        Instant start = BASE.plus(Duration.ofHours(hour));
        return new NewRun(UUID.randomUUID(), 1, "pump_watch", 7, 1, 2, Duration.ofMinutes(15),
                new TimeWindow(start, start.plus(Duration.ofHours(1))), null, "schedule", "user-1", "user-1", null);
    }
}
