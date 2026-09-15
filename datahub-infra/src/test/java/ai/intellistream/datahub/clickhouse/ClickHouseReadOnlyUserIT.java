// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.clickhouse;

import ai.intellistream.datahub.models.EventModel;
import ai.intellistream.datahub.models.events.EventRetreiver;
import ai.intellistream.datahub.services.ValkeyService;
import ai.intellistream.datahub.tenant.Tenant;
import ai.intellistream.datahub.tenant.TenantConfigService;
import ai.intellistream.datahub.tenant.TenantContext;
import com.clickhouse.client.api.Client;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The events filter, executed by a user that holds nothing but {@code SELECT}.
 *
 * <p>Two claims, and neither follows from the other. That a {@code SELECT}-only user <em>can</em>
 * run this query is not obvious: the filter language reaches for map access, array functions and
 * bound parameters of half a dozen types, and a missing grant shows up as a runtime error on one
 * expression rather than as a connection failure. That such a user <em>cannot</em> write is the
 * entire point of provisioning it, and is worth proving against a real server rather than assumed
 * from a {@code GRANT} statement written in another repository.
 *
 * <p>The container needs its own server because it has to create users, which the shared one's
 * account cannot do — {@code CLICKHOUSE_DEFAULT_ACCESS_MANAGEMENT} is off by default in the image.
 *
 * <p>Run with {@code ./gradlew :datahub-infra:integrationTest} on a host with Docker/Podman.
 */
@Tag("integration")
@Testcontainers
class ClickHouseReadOnlyUserIT {

    private static final String TENANT = "acme";

    /**
     * Access management on, so the test can provision the same two users the tenant manager
     * provisions: an owner with {@code ALL} and a reader with {@code SELECT}.
     */
    @Container
    static final ClickHouseContainer CLICKHOUSE =
            new ClickHouseContainer(DockerImageName.parse("clickhouse/clickhouse-server:26.8.2.7"))
                    .withUsername("admin")
                    .withPassword("admin")
                    .withEnv("CLICKHOUSE_DEFAULT_ACCESS_MANAGEMENT", "1")
                    .waitingFor(Wait.forHttp("/ping").forPort(8123).forStatusCode(200));

    private static Client admin;
    private static Client owner;
    private static Client reader;
    private static ClickHouseEventService service;

    @BeforeAll
    static void setUp() {
        admin = clientFor("default", "admin", "admin");
        exec(admin, "CREATE DATABASE " + TENANT);

        // The production events DDL, so the reader is exercised against the real column types.
        exec(admin, """
                CREATE TABLE acme.events (
                    id                                   UUID,
                    external_id                          LowCardinality(String),
                    external_id_hash                     Int128,
                    type                                 LowCardinality(String),
                    sub_type                             Nullable(String),
                    status                               Nullable(String),
                    description                          String,
                    data_set_id                          Int64,
                    source                               LowCardinality(String),
                    date_created                         DateTime64(3, 'UTC'),
                    last_updated                         DateTime64(3, 'UTC'),
                    event_time                           DateTime64(3, 'UTC'),
                    related_resources_id                 Array(Int64),
                    related_resources_external_id        Array(LowCardinality(String)),
                    related_resources_external_id_hash   Array(Int64),
                    metadata                             Map(LowCardinality(String), String),
                    INDEX events_external_id_idx external_id TYPE ngrambf_v1(3, 1024, 5, 0) GRANULARITY 8,
                    INDEX events_metadata_values_idx mapValues(metadata) TYPE bloom_filter GRANULARITY 8
                ) ENGINE = ReplacingMergeTree
                  ORDER BY id
                  PARTITION BY (toYYYYMM(event_time))
                """);

        // Exactly what the tenant manager runs when it provisions a tenant.
        exec(admin, "CREATE USER acme_owner IDENTIFIED BY 'owner-secret'");
        exec(admin, "GRANT ALL ON acme.* TO acme_owner");
        exec(admin, "CREATE USER acme_reader IDENTIFIED BY 'reader-secret'");
        exec(admin, "GRANT SELECT ON acme.* TO acme_reader");

        owner = clientFor(TENANT, "acme_owner", "owner-secret");
        reader = clientFor(TENANT, "acme_reader", "reader-secret");

        exec(owner, """
                INSERT INTO events (id, external_id, external_id_hash, type, sub_type, status, description,
                    data_set_id, source, date_created, last_updated, event_time,
                    related_resources_id, related_resources_external_id, related_resources_external_id_hash, metadata)
                VALUES ('0193a4b5-6c7d-7e8f-9012-3456789ab001', 'alarm_pipe_overpressure', 0, 'alarm',
                    'water', 'open', 'desc', 12, 'sensor',
                    '2026-04-22 14:30:00.000', '2026-04-22 14:30:00.000', '2026-04-22 14:30:00.000',
                    [], [], [], {'line': '7'})
                """);

        TenantConfigService tenantConfigService = mock(TenantConfigService.class);
        ClickHouseClientPool pool = mock(ClickHouseClientPool.class);
        Tenant tenant = new Tenant();
        tenant.setOrganizationId(TENANT);
        when(tenantConfigService.getConfig(anyString())).thenReturn(tenant);
        when(pool.getClient(any(Tenant.class))).thenReturn(owner);
        when(pool.getReadOnlyClient(any(Tenant.class))).thenReturn(reader);

        service = new ClickHouseEventService(tenantConfigService, mock(ValkeyService.class), pool);
        TenantContext.setTenantId(TENANT);
    }

    @AfterAll
    static void tearDown() {
        TenantContext.clear();
        for (Client c : new Client[]{reader, owner, admin}) {
            if (c != null) c.close();
        }
    }

    private static Client clientFor(String database, String user, String password) {
        return new Client.Builder()
                .addEndpoint("http://" + CLICKHOUSE.getHost() + ":" + CLICKHOUSE.getMappedPort(8123))
                .setUsername(user)
                .setPassword(password)
                .setDefaultDatabase(database)
                // Mirrors ClickHouseClientPool#build: these are session settings a SELECT-only user
                // still has to be allowed to set, which is itself part of what this test covers.
                .serverSetting("max_threads", "8")
                .serverSetting("async_insert", "1")
                .serverSetting("session_timezone", "UTC")
                .build();
    }

    private static void exec(Client client, String sql) {
        try (var ignored = client.query(sql).get()) {
            // DDL and INSERT return nothing worth reading.
        } catch (Exception e) {
            throw new IllegalStateException("ClickHouse statement failed: " + sql, e);
        }
    }

    private static EventRetreiver expression(String advancedFilter) {
        EventRetreiver retreiver = new EventRetreiver();
        retreiver.setAdvancedFilter(advancedFilter);
        return retreiver;
    }

    @Test
    void theReaderCanRunAPlainFilter() {
        List<EventModel> events = service.filter(new EventRetreiver());

        assertThat(events).extracting(EventModel::getExternalId).containsExactly("alarm_pipe_overpressure");
    }

    /**
     * The caller-authored half: a filter expression, which is the reason the reader exists. It
     * exercises a comparison, a LIKE, a nullable column and a metadata map lookup — the parts of
     * the language that turn into something other than a bare column reference.
     */
    @Test
    void theReaderCanRunAFilterExpression() {
        assertThat(service.filter(expression(
                "type = 'alarm' AND externalId LIKE 'alarm_%' AND subType = 'water'")))
                .hasSize(1);

        assertThat(service.filter(expression("metadata['line'] = '7' AND dataSetId = 12")))
                .hasSize(1);

        assertThat(service.filter(expression("status IS NOT NULL AND to_int(metadata['line']) > 99")))
                .isEmpty();
    }

    /**
     * The other half of the bargain. If the renderer ever emitted something that got out of the
     * WHERE clause, this is the privilege that stops it being a write.
     */
    @Test
    void theReaderCannotWrite() {
        assertThatThrownBy(() -> exec(reader, "ALTER TABLE events DELETE WHERE 1 = 1"))
                .hasMessageContaining("ClickHouse statement failed");
        assertThatThrownBy(() -> exec(reader, "DROP TABLE events"))
                .hasMessageContaining("ClickHouse statement failed");
        assertThatThrownBy(() -> exec(reader, "TRUNCATE TABLE events"))
                .hasMessageContaining("ClickHouse statement failed");

        // Still there, and still readable by the same user that was just refused.
        assertThat(service.filter(new EventRetreiver())).hasSize(1);
    }

    /** Nor can it reach past its own database into another tenant's. */
    @Test
    void theReaderCannotSeeAnotherDatabase() {
        exec(admin, "CREATE DATABASE IF NOT EXISTS globex");
        exec(admin, "CREATE TABLE IF NOT EXISTS globex.secrets (v String) ENGINE = MergeTree ORDER BY v");

        assertThatThrownBy(() -> exec(reader, "SELECT * FROM globex.secrets"))
                .hasMessageContaining("ClickHouse statement failed");
    }
}
