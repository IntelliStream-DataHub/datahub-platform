// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.cleanup.flow;

import ai.intellistream.datahub.clickhouse.ClickHouseClientPool;
import ai.intellistream.datahub.tenant.Tenant;
import ai.intellistream.datahub.tenant.TenantConfigService;
import com.clickhouse.client.api.Client;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.stream.Collectors;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Flow retention against a tenant's real tables, as the platform's {@code clickhouse.sql} makes
 * them: both expire together, and the TTL is altered only when it differs from the period.
 */
@Tag("integration")
class FlowRetentionTaskIT {

    private static ClickHouseContainer clickhouse;
    private static Client ch;

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
            if (statement.contains("flow_run_event (") || statement.contains("flow_run (")) {
                ch.query(statement).get().close();
            }
        }
    }

    @AfterAll
    static void stop() {
        if (ch != null) ch.close();
        if (clickhouse != null) clickhouse.stop();
    }

    @Test
    void bothTablesExpireAfterThePeriodAndTheTtlIsAlteredOnlyWhenItDiffers() {
        assertThat(ttl("flow_run_event")).contains("toIntervalDay(90)");
        assertThat(ttl("flow_run")).contains("toIntervalDay(90)");
        assertThat(ttl("flow_run")).as("by when the run ended").contains("toDateTime(finished_at)");
        Tenant tenant = new Tenant();
        tenant.setOrganizationId("t");

        task(Duration.ofDays(90)).expire(tenant);
        assertThat(ttl("flow_run")).contains("toIntervalDay(90)");

        assertThat(task(Duration.ofDays(30)).alignTtl(ch, "t", "flow_run", "finished_at")).isTrue();
        assertThat(ttl("flow_run")).contains("toIntervalDay(30)");
        assertThat(ttl("flow_run_event")).as("each table on its own").contains("toIntervalDay(90)");
        assertThat(task(Duration.ofDays(30)).alignTtl(ch, "t", "flow_run", "finished_at")).isFalse();

        task(Duration.ofDays(30)).expire(tenant);
        assertThat(ttl("flow_run_event")).contains("toIntervalDay(30)");
        assertThat(ttl("flow_run")).contains("toIntervalDay(30)");

        task(Duration.ofDays(90)).expire(tenant);
        assertThat(ttl("flow_run_event")).contains("toIntervalDay(90)");
        assertThat(ttl("flow_run")).contains("toIntervalDay(90)");
    }

    @Test
    void aTenantWithoutTheTableYetIsLeftAlone() {
        assertThat(task(Duration.ofDays(10)).alignTtl(ch, "t", "flow_run_not_made_yet", "finished_at")).isFalse();
    }

    private FlowRetentionTask task(Duration retention) {
        return new FlowRetentionTask(mock(TenantConfigService.class), (Tenant t) -> ch, retention);
    }

    private static String ttl(String table) {
        return ch.queryAll("SELECT create_table_query FROM system.tables WHERE database = currentDatabase() "
                + "AND name = '" + table + "'").getFirst().getString("create_table_query");
    }
}
