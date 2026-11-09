// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.flow.records;

import ai.intellistream.datahub.clickhouse.ClickHouseClientPool;
import ai.intellistream.datahub.flow.api.ContentKind;
import ai.intellistream.datahub.flow.engine.FlowRecord;
import ai.intellistream.datahub.flow.records.RecordsTable.Position;
import ai.intellistream.datahub.flow.records.RecordsTable.StoredRecord;
import ai.intellistream.datahub.tenant.PulsarTenant;
import ai.intellistream.datahub.tenant.Tenant;
import ai.intellistream.datahub.tenant.TenantConfigService;
import ai.intellistream.datahub.tenant.TenantFeatures;
import com.clickhouse.client.api.Client;
import com.clickhouse.client.api.query.GenericRecord;
import org.apache.pulsar.client.admin.PulsarAdmin;
import org.apache.pulsar.client.api.PulsarClient;
import org.apache.pulsar.common.policies.data.TenantInfo;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.containers.PulsarContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Records from a run to ClickHouse through each tenant's records topic, with a real broker and a
 * real ClickHouse: in order, once each despite redelivery, waiting out a tenant whose ClickHouse
 * refuses without holding up another tenant, and capped with a marker.
 */
@Tag("integration")
class RecordsPipelineIT {

    private static final String TENANT_A = "org-a";
    private static final String TENANT_B = "org-b";

    private static PulsarContainer pulsar;
    private static ClickHouseContainer clickhouse;
    private static PulsarAdmin admin;
    private static PulsarClient client;
    private static Client chA;
    private static Client chB;
    private static Client chBroken;
    private static volatile boolean tenantBDown;
    private static RecordsPublisher publisher;
    private static RecordsConsumers consumers;

    @BeforeAll
    static void start() throws Exception {
        String version = System.getProperty("datahub.pulsar.image.version", "5.0.0");
        pulsar = new PulsarContainer(DockerImageName.parse("apachepulsar/pulsar:" + version))
                .withStartupTimeout(Duration.ofMinutes(3));
        clickhouse = new ClickHouseContainer(DockerImageName.parse("clickhouse/clickhouse-server:26.8.2.7"))
                .withUsername("tester").withPassword("test")
                .waitingFor(Wait.forHttp("/ping").forPort(8123).forStatusCode(200));
        pulsar.start();
        clickhouse.start();

        admin = PulsarAdmin.builder().serviceHttpUrl(pulsar.getHttpServiceUrl()).build();
        for (String t : new String[]{"pulsar-a", "pulsar-b"}) {
            admin.tenants().createTenant(t, TenantInfo.builder().allowedClusters(Set.of("standalone")).build());
        }
        client = PulsarClient.builder().serviceUrl(pulsar.getPulsarBrokerUrl()).build();

        chA = database("records_a");
        chB = database("records_b");
        chBroken = ClickHouseClientPool.transport(new Client.Builder()
                        .addEndpoint("http://" + clickhouse.getHost() + ":" + clickhouse.getMappedPort(8123))
                        .setUsername("tester").setPassword("wrong")
                        .setDefaultDatabase("records_b"))
                .build();

        TenantConfigService registry = mock(TenantConfigService.class);
        Map<String, Tenant> tenants = Map.of(TENANT_A, tenant(TENANT_A, "pulsar-a"),
                TENANT_B, tenant(TENANT_B, "pulsar-b"));
        registry.cachedTenants = tenants;
        tenants.forEach((id, t) -> when(registry.getConfig(id)).thenReturn(t));

        RecordsTopicProvisioner provisioner = new RecordsTopicProvisioner(admin, registry,
                DataSize.ofMegabytes(64), Duration.ofDays(1), DataSize.ofMegabytes(128), Duration.ofDays(2));
        provisioner.provisionAll();
        assertThat(provisioner.provisioned()).containsExactlyInAnyOrder(TENANT_A, TENANT_B);

        publisher = new RecordsPublisher(client, registry, 100_000);
        consumers = new RecordsConsumers(client, registry, provisioner,
                t -> TENANT_A.equals(t.getOrganizationId()) ? chA : tenantBDown ? chBroken : chB,
                Duration.ofMillis(300));
        consumers.startNew();
    }

    @AfterAll
    static void stop() throws Exception {
        if (consumers != null) consumers.close();
        if (publisher != null) publisher.close();
        if (client != null) client.close();
        if (admin != null) admin.close();
        for (Client c : new Client[]{chA, chB, chBroken}) {
            if (c != null) c.close();
        }
        if (pulsar != null) pulsar.stop();
        if (clickhouse != null) clickhouse.stop();
    }

    @Test
    void aRunsRecordsLandInTheOrderTheyHappened() throws Exception {
        UUID run = UUID.randomUUID();
        RunRecords records = publisher.open(TENANT_A, run, 1, "temp_hourly", 3, 7L);
        for (int i = 0; i < 2_500; i++) {
            records.accept(record(run, "p" + i, null));
        }
        records.finish(Duration.ofSeconds(30));

        await().atMost(Duration.ofSeconds(60)).until(() -> count(chA, run) == 2_500);
        List<StoredRecord> page = RecordsTable.list(chA, run, null, 10_000);
        assertThat(page).extracting(StoredRecord::processor)
                .containsExactlyElementsOf(java.util.stream.IntStream.range(0, 2_500).mapToObj(i -> "p" + i).toList());
        assertThat(page).extracting(StoredRecord::sequence).containsOnly(0, 1, 2);

        // Paging picks up exactly where the last page ended.
        List<StoredRecord> first = RecordsTable.list(chA, run, null, 1_200);
        StoredRecord last = first.getLast();
        List<StoredRecord> rest = RecordsTable.list(chA, run,
                new Position(last.attempt(), last.sequence(), last.ordinal()), 10_000);
        assertThat(first.size() + rest.size()).isEqualTo(2_500);
        assertThat(rest.getFirst().processor()).isEqualTo("p1200");
    }

    @Test
    void aBatchInsertedBeforeItsAcknowledgementIsNotStoredTwice() throws Exception {
        UUID run = UUID.randomUUID();
        // A consumer inserted this batch and died before acknowledging it...
        FlowRecordBatch batch = new FlowRecordBatch();
        batch.setRunId(run.toString());
        batch.setAttempt(1);
        batch.setSequence(0);
        batch.setFlowExternalId("temp_hourly");
        batch.setFlowVersion(3);
        batch.setRecords(List.of(FlowRecordBatch.Row.of(record(run, "a", null)),
                FlowRecordBatch.Row.of(record(run, "b", null))));
        RecordsTable.insert(chA, batch, 30);

        // ...so Pulsar delivers it again, to whichever consumer has the run's key now.
        RunRecords records = publisher.open(TENANT_A, run, 1, "temp_hourly", 3, 7L);
        records.accept(record(run, "a", null));
        records.accept(record(run, "b", null));
        records.finish(Duration.ofSeconds(30));

        // A second run is the marker that the consumer has moved past the redelivered batch.
        UUID after = UUID.randomUUID();
        RunRecords next = publisher.open(TENANT_A, after, 1, "temp_hourly", 3, 7L);
        next.accept(record(after, "c", null));
        next.finish(Duration.ofSeconds(30));
        await().atMost(Duration.ofSeconds(60)).until(() -> count(chA, after) == 1);
        assertThat(count(chA, run)).isEqualTo(2);
    }

    @Test
    void aTenantWhoseClickHouseRefusesWaitsWithoutHoldingUpAnother() throws Exception {
        tenantBDown = true;
        UUID runB = UUID.randomUUID();
        RunRecords b = publisher.open(TENANT_B, runB, 1, "flow_b", 1, 7L);
        b.accept(record(runB, "b", null));
        b.finish(Duration.ofSeconds(30));   // Pulsar has them: the run may finish

        UUID runA = UUID.randomUUID();
        RunRecords a = publisher.open(TENANT_A, runA, 1, "flow_a", 1, 7L);
        a.accept(record(runA, "a", null));
        a.finish(Duration.ofSeconds(30));

        await().atMost(Duration.ofSeconds(60)).until(() -> count(chA, runA) == 1);
        Thread.sleep(1_500);
        assertThat(count(chB, runB)).isZero();

        tenantBDown = false;
        await().atMost(Duration.ofSeconds(90)).until(() -> count(chB, runB) == 1);
    }

    @Test
    void anAttemptOverTheCapEndsWithAMarkerRow() throws Exception {
        UUID run = UUID.randomUUID();
        RunRecords records = new RecordsPublisher(client, registryFor(), 10)
                .open(TENANT_A, run, 2, "capped", 1, 7L);
        for (int i = 0; i < 15; i++) {
            records.accept(record(run, "p" + i, null));
        }
        records.finish(Duration.ofSeconds(30));

        await().atMost(Duration.ofSeconds(60)).until(() -> count(chA, run) == 11);
        List<StoredRecord> rows = RecordsTable.list(chA, run, null, 100);
        assertThat(rows.getLast().action()).isEqualTo(FlowRecordBatch.Row.TRUNCATED);
        assertThat(rows.getLast().truncated()).isTrue();
        assertThat(rows.subList(0, 10)).noneMatch(StoredRecord::truncated);
    }

    @Test
    void theLookupFindsTheLatestRecordedWriteCoveringTheTimeInTheCallersDatasets() throws Exception {
        UUID earlier = UUID.randomUUID();
        UUID later = UUID.randomUUID();
        String uri = "datahub://timeseries/ts%3Alookup?from=2026-10-12T10:00:00Z&to=2026-10-12T11:00:00Z";
        for (UUID run : List.of(earlier, later)) {
            RunRecords records = publisher.open(TENANT_A, run, 1, run == later ? "flow_later" : "flow_earlier",
                    1, run == later ? 8L : 7L);
            records.accept(record(run, "out", uri));
            records.finish(Duration.ofSeconds(30));
            await().atMost(Duration.ofSeconds(60)).until(() -> count(chA, run) == 1);
            Thread.sleep(10);
        }
        Instant at = Instant.parse("2026-10-12T10:30:00Z");

        assertThat(RecordsTable.latestWrite(chA, "ts:lookup", at, null))
                .contains(new RecordsTable.Write(later, "flow_later"));
        assertThat(RecordsTable.latestWrite(chA, "ts:lookup", at, List.of(7L)))
                .contains(new RecordsTable.Write(earlier, "flow_earlier"));
        assertThat(RecordsTable.latestWrite(chA, "ts:lookup", at, List.of())).isEmpty();
        assertThat(RecordsTable.latestWrite(chA, "ts:lookup", Instant.parse("2026-10-12T11:00:00Z"), null))
                .isEmpty();
    }

    private static TenantConfigService registryFor() {
        TenantConfigService registry = mock(TenantConfigService.class);
        when(registry.getConfig(TENANT_A)).thenReturn(tenant(TENANT_A, "pulsar-a"));
        return registry;
    }

    private static FlowRecord record(UUID run, String processor, String transitUri) {
        return new FlowRecord(Instant.now(), run, 1, processor, "test.processor",
                transitUri == null ? FlowRecord.Action.CREATE : FlowRecord.Action.SEND, UUID.randomUUID(),
                List.of(), "success", ContentKind.TIMESERIES, 10, -7, Map.of("k", "v"), transitUri, 1);
    }

    private static long count(Client ch, UUID run) {
        List<GenericRecord> rows = ch.queryAll("SELECT count() AS n FROM flow_run_event WHERE run_id = '"
                + run + "'");
        return rows.getFirst().getLong("n");
    }

    private static Client database(String name) throws Exception {
        String endpoint = "http://" + clickhouse.getHost() + ":" + clickhouse.getMappedPort(8123);
        try (Client admin = ClickHouseClientPool.transport(new Client.Builder().addEndpoint(endpoint)
                .setUsername("tester").setPassword("test")).build()) {
            admin.query("CREATE DATABASE IF NOT EXISTS " + name).get().close();
        }
        Client c = ClickHouseClientPool.transport(new Client.Builder().addEndpoint(endpoint)
                        .setUsername("tester").setPassword("test").setDefaultDatabase(name))
                .serverSetting("async_insert", "1")
                .serverSetting("session_timezone", "UTC")
                .build();
        String schema = Files.readAllLines(Path.of("../datahub-api/src/main/resources/db/clickhouse.sql"))
                .stream().filter(line -> !line.strip().startsWith("--")).collect(Collectors.joining("\n"));
        for (String statement : schema.split(";")) {
            if (statement.contains("flow_run_event")) {
                c.query(statement).get().close();
            }
        }
        return c;
    }

    private static Tenant tenant(String orgId, String pulsarTenant) {
        Tenant tenant = new Tenant();
        tenant.setOrganizationId(orgId);
        PulsarTenant p = new PulsarTenant();
        p.setTenant(pulsarTenant);
        tenant.setPulsarTenant(p);
        TenantFeatures features = new TenantFeatures();
        features.setFlowFeatureEnabled(true);
        tenant.setFeatures(features);
        return tenant;
    }
}
