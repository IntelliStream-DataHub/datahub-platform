// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.clickhouse;

import ai.intellistream.datahub.api.responses.DataWrapper;
import ai.intellistream.datahub.errors.ObjectNotFoundException;
import ai.intellistream.datahub.models.EventModel;
import ai.intellistream.datahub.services.ValkeyService;
import ai.intellistream.datahub.tenant.Tenant;
import ai.intellistream.datahub.tenant.TenantConfigService;
import ai.intellistream.datahub.tenant.TenantContext;
import ai.intellistream.datahub.testsupport.SharedClickHouse;
import com.clickhouse.client.api.Client;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Regression coverage for the read paths of {@link ClickHouseEventService} against a real ClickHouse
 * server — the SQL these build only fails at execution time, so a container is required. Guards three
 * bugs that shipped broken and returned nothing / errored:
 * <ul>
 *   <li>{@code count()} read the count column by index {@code 0} (the client is 1-based) → threw;</li>
 *   <li>{@code search()} and {@code findById()} selected the {@code e.}-qualified {@code EVENT_COLUMNS}
 *       from {@code FROM events} with no {@code e} alias → ClickHouse rejected the identifier.</li>
 * </ul>
 * Run with {@code ./gradlew :datahub-infra:integrationTest} on a host with Docker/Podman.
 */
@Tag("integration")
class ClickHouseEventReadIT {

    private static final String TENANT = "default";

    private static final UUID PIPE_ID = UUID.fromString("0193a4b5-6c7d-7e8f-9012-3456789abcde");
    private static final UUID CALIB_ID = UUID.fromString("0193a4b5-6c7d-7e8f-9012-3456789abcdf");
    private static final UUID TEMP_ID = UUID.fromString("0193a4b5-6c7d-7e8f-9012-3456789abce0");


    static Client client;
    static ClickHouseEventService service;

    @BeforeAll
    static void setUp() throws Exception {
        client = SharedClickHouse.newClient("event_read_it");

        // Production events DDL (datahub-api/src/main/resources/db/clickhouse.sql). Indexes kept so the
        // ILIKE/ngram paths run against the same shape as prod.
        SharedClickHouse.execute(client, """
                CREATE TABLE events (
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

        // Three events chosen so "pipe" matches via external_id (row 1), metadata value (row 2) but not
        // row 3, while "ambient" isolates the description-only branch.
        insert(PIPE_ID, "alarm_pipe_overpressure", "alarm", "overpressure", "Pipe A1212 exceeded 40 bar",
                12, "{'severity':'high'}");
        insert(CALIB_ID, "calib_2026_04", "calibration", "routine", "Quarterly recalibration",
                12, "{'location':'pipe_station_4'}");
        insert(TEMP_ID, "temp_reading_88", "measurement", "sample", "Ambient temperature nominal",
                34, "{'severity':'low'}");

        TenantConfigService tenantConfigService = mock(TenantConfigService.class);
        ClickHouseClientPool pool = mock(ClickHouseClientPool.class);
        ValkeyService valkey = mock(ValkeyService.class);
        Tenant tenant = new Tenant();
        tenant.setOrganizationId(TENANT);
        when(tenantConfigService.getConfig(anyString())).thenReturn(tenant);
        when(pool.getClient(any(Tenant.class))).thenReturn(client);

        service = new ClickHouseEventService(tenantConfigService, valkey, pool);
        TenantContext.setTenantId(TENANT);
    }

    @AfterAll
    static void tearDown() {
        TenantContext.clear();
        if (client != null) client.close();
    }

    private static void insert(UUID id, String externalId, String type, String subType,
                               String description, long dataSetId, String metadata) throws Exception {
        SharedClickHouse.execute(client, ("""
                INSERT INTO events (id, external_id, external_id_hash, type, sub_type, status, description,
                    data_set_id, source, date_created, last_updated, event_time,
                    related_resources_id, related_resources_external_id, related_resources_external_id_hash, metadata)
                VALUES ('%s', '%s', 0, '%s', '%s', 'open', '%s', %d, 'sensor',
                    '2026-04-22 14:30:00.000', '2026-04-22 14:30:00.000', '2026-04-22 14:30:00.000',
                    [], [], [], %s)
                """).formatted(id, externalId, type, subType, description, dataSetId, metadata));
    }

    @Test
    void countReturnsTotalRows() {
        // Before the fix this threw (getLong(0) — the client is 1-based), surfacing as a 500.
        assertEquals(3L, service.count());
    }

    @Test
    void searchMatchesExternalIdAndMetadataValue() {
        // "pipe" hits row 1 via external_id and row 2 via a metadata value, but not row 3.
        List<EventModel> hits = service.search("pipe", 100);
        Set<String> ids = idsOf(hits);
        assertEquals(Set.of(PIPE_ID.toString(), CALIB_ID.toString()), ids,
                "search should match external_id and metadata values, case-insensitively");
    }

    @Test
    void searchMatchesDescriptionOnly() {
        // "ambient" appears only in row 3's description — isolates the description branch.
        List<EventModel> hits = service.search("ambient", 100);
        assertEquals(Set.of(TEMP_ID.toString()), idsOf(hits));
    }

    @Test
    void searchIsCaseInsensitive() {
        assertEquals(Set.of(PIPE_ID.toString(), CALIB_ID.toString()), idsOf(service.search("PIPE", 100)));
    }

    @Test
    void searchEmptyQueryReturnsRecentEvents() {
        // Null/blank query drops the text predicate and returns the most recent events.
        assertEquals(3, service.search(null, 100).size());
    }

    @Test
    void searchHonoursDatasetAcl() {
        // ACL limited to dataset 34 leaves only row 3, which "pipe" does not match → empty.
        assertTrue(service.search("pipe", 100, List.of(34L)).isEmpty());
        // Same ACL, a query that does match row 3.
        assertEquals(Set.of(TEMP_ID.toString()), idsOf(service.search("ambient", 100, List.of(34L))));
    }

    @Test
    void findByIdReturnsTheEvent() {
        // Before the fix this threw on the unresolved e.* identifier.
        DataWrapper<EventModel> found = service.findById(PIPE_ID.toString());
        assertEquals(1, found.getItems().size());
        assertEquals("alarm_pipe_overpressure", found.getItems().iterator().next().getExternalId());
    }

    @Test
    void findByIdMissingThrowsNotFound() {
        assertThrows(ObjectNotFoundException.class,
                () -> service.findById(UUID.randomUUID().toString()));
    }

    @Test
    void findByIdHonoursDatasetAcl() {
        // PIPE_ID lives in dataset 12; an ACL that excludes it makes the event invisible → not found.
        assertThrows(ObjectNotFoundException.class,
                () -> service.findById(PIPE_ID.toString(), List.of(34L)));
    }

    private static Set<String> idsOf(List<EventModel> events) {
        return events.stream().map(EventModel::getId).collect(java.util.stream.Collectors.toSet());
    }
}
