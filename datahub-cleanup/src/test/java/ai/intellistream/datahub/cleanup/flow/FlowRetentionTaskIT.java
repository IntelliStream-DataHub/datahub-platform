// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.cleanup.flow;

import ai.intellistream.datahub.clickhouse.ClickHouseClientPool;
import ai.intellistream.datahub.testsupport.SharedPostgres;
import ai.intellistream.datahub.tenant.Tenant;
import ai.intellistream.datahub.tenant.TenantConfigService;
import com.clickhouse.client.api.Client;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Flow retention against a tenant's real tables: {@code flow.run} as V46 creates it, and
 * {@code flow_run_event} as the platform's {@code clickhouse.sql} does.
 */
@Tag("integration")
class FlowRetentionTaskIT {

    private static ClickHouseContainer clickhouse;
    private static Client ch;
    private static JdbcTemplate jdbc;

    @BeforeAll
    static void start() throws Exception {
        clickhouse = new ClickHouseContainer(DockerImageName.parse("clickhouse/clickhouse-server:26.8.2.7"))
                .withUsername("tester").withPassword("test")
                .waitingFor(Wait.forHttp("/ping").forPort(8123).forStatusCode(200));
        clickhouse.start();
        String endpoint = "http://" + clickhouse.getHost() + ":" + clickhouse.getMappedPort(8123);
        try (Client admin = ClickHouseClientPool.transport(new Client.Builder().addEndpoint(endpoint)
                .setUsername("tester").setPassword("test")).build()) {
            admin.query("CREATE DATABASE IF NOT EXISTS retention").get().close();
        }
        ch = ClickHouseClientPool.transport(new Client.Builder().addEndpoint(endpoint)
                .setUsername("tester").setPassword("test").setDefaultDatabase("retention")).build();
        String schema = Files.readAllLines(Path.of("../datahub-api/src/main/resources/db/clickhouse.sql"))
                .stream().filter(line -> !line.strip().startsWith("--")).collect(Collectors.joining("\n"));
        for (String statement : schema.split(";")) {
            if (statement.contains(FlowRetentionTask.TABLE)) {
                ch.query(statement).get().close();
            }
        }

        jdbc = new JdbcTemplate(new DriverManagerDataSource(SharedPostgres.newDatabase("flow_retention_it"),
                SharedPostgres.username(), SharedPostgres.password()));
    }

    @AfterAll
    static void stop() {
        if (ch != null) ch.close();
        if (clickhouse != null) clickhouse.stop();
    }

    @Test
    void finishedRunsOlderThanThePeriodAreDeletedInBatchesAndOwedOnesKept() {
        // One statement, so the flow and its first version meet the deferred key in one transaction.
        long flow = jdbc.queryForObject("WITH f AS (INSERT INTO flow.flow (external_id, data_set_id, "
                + "data_set_external_id, name, current_version, created_by, updated_by) "
                + "VALUES ('retained', 1, 'ds', 'n', 1, 'u', 'u') RETURNING id), "
                + "v AS (INSERT INTO flow.flow_version (flow_id, version, definition, created_by) "
                + "SELECT id, 1, '{}', 'u' FROM f) SELECT id FROM f", Long.class);
        UUID[] old = new UUID[5];
        for (int i = 0; i < old.length; i++) {
            old[i] = run(flow, "SUCCEEDED", "100 days", false);
        }
        UUID oldFailed = run(flow, "FAILED", "100 days", false);
        UUID owed = run(flow, "FAILED", "100 days", true);
        UUID recent = run(flow, "SUCCEEDED", "10 days", false);
        UUID pending = run(flow, "PENDING", null, false);

        FlowRetentionTask task = task(Duration.ofDays(90), 2);
        assertThat(task.deleteExpiredRuns()).isEqualTo(6);

        assertThat(jdbc.queryForList("SELECT id FROM flow.run", UUID.class))
                .containsExactlyInAnyOrder(owed, recent, pending)
                .doesNotContain(oldFailed);
        assertThat(task.deleteExpiredRuns()).isZero();
    }

    @Test
    void theRecordsTtlIsSetToThePeriodOnlyWhenItDiffers() {
        assertThat(ttl()).contains("toIntervalDay(90)");
        assertThat(task(Duration.ofDays(90), 1000).alignTtl(ch, "t")).isFalse();

        assertThat(task(Duration.ofDays(30), 1000).alignTtl(ch, "t")).isTrue();
        assertThat(ttl()).contains("toIntervalDay(30)");
        assertThat(task(Duration.ofDays(30), 1000).alignTtl(ch, "t")).isFalse();

        assertThat(task(Duration.ofDays(90), 1000).alignTtl(ch, "t")).isTrue();
        assertThat(ttl()).contains("toIntervalDay(90)");
    }

    private FlowRetentionTask task(Duration retention, int batch) {
        return new FlowRetentionTask(mock(TenantConfigService.class), jdbc, (Tenant t) -> ch,
                retention, batch);
    }

    private static String ttl() {
        return ch.queryAll("SELECT create_table_query FROM system.tables WHERE database = currentDatabase() "
                + "AND name = '" + FlowRetentionTask.TABLE + "'").getFirst().getString("create_table_query");
    }

    private static UUID run(long flow, String status, String age, boolean owed) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO flow.run (id, flow_id, version, status, max_attempts, "
                        + "run_timeout, trigger, failure_event_owed, finished_at) VALUES (?, ?, 1, ?, 1, "
                        + "interval '1 minute', 'manual', ?, " + (age == null ? "NULL" : "now() - interval '" + age + "'")
                        + ")",
                id, flow, status, owed);
        return id;
    }
}
