// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.clickhouse;

import ai.intellistream.datahub.models.EventModel;
import ai.intellistream.datahub.models.IdCollection;
import ai.intellistream.datahub.models.events.EventFilter;
import ai.intellistream.datahub.models.events.EventQueryResult;
import ai.intellistream.datahub.models.events.EventRetreiver;
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
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Dataset filtering on {@link ClickHouseEventService}'s two query paths — {@code filter} (drill-down)
 * and {@code aggregate} (grouped counts) — against a real ClickHouse server.
 *
 * <p>The distinction these pin is between a {@code dataSetId} that is <b>null</b> and one that is
 * <b>empty</b>. Null means "do not narrow by dataset" and must drop the predicate; empty means "narrow
 * to no datasets" and must match nothing. They used to be conflated: datahub-api coerces null
 * collections to empty, and neither WHERE builder guarded the empty case, so {@code "dataSetId": null}
 * produced {@code data_set_id IN []} and silently returned zero events.
 *
 * <p>The two paths build their {@code WHERE} clauses separately, so both are exercised here — that
 * duplication is exactly why they share {@code dataSetIdCondition}.
 *
 * <p>Run with {@code ./gradlew :datahub-infra:integrationTest} on a host with Docker/Podman.
 */
@Tag("integration")
class ClickHouseEventFilterIT {

    private static final String TENANT = "default";

    private static final UUID ALARM_12 = UUID.fromString("0193a4b5-6c7d-7e8f-9012-3456789ab001");
    private static final UUID CALIB_12 = UUID.fromString("0193a4b5-6c7d-7e8f-9012-3456789ab002");
    private static final UUID ALARM_34 = UUID.fromString("0193a4b5-6c7d-7e8f-9012-3456789ab003");
    private static final UUID ALARM_56 = UUID.fromString("0193a4b5-6c7d-7e8f-9012-3456789ab004");


    static Client client;
    static ClickHouseEventService service;

    @BeforeAll
    static void setUp() throws Exception {
        client = SharedClickHouse.newClient("event_filter_it");

        // Production events DDL, as in ClickHouseEventReadIT (datahub-api/src/main/resources/db/clickhouse.sql).
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

        // Four events over three datasets. Dataset 12 holds two of different types, so a dataset
        // filter can be combined with a type filter and still be distinguishable from either alone.
        insert(ALARM_12, "alarm_pipe_overpressure", "alarm", 12);
        insert(CALIB_12, "calib_2026_04", "calibration", 12);
        insert(ALARM_34, "alarm_valve_stuck", "alarm", 34);
        insert(ALARM_56, "alarm_tank_level", "alarm", 56);

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

    private static void insert(UUID id, String externalId, String type, long dataSetId) throws Exception {
        SharedClickHouse.execute(client, ("""
                INSERT INTO events (id, external_id, external_id_hash, type, sub_type, status, description,
                    data_set_id, source, date_created, last_updated, event_time,
                    related_resources_id, related_resources_external_id, related_resources_external_id_hash, metadata)
                VALUES ('%s', '%s', 0, '%s', 'routine', 'open', 'desc', %d, 'sensor',
                    '2026-04-22 14:30:00.000', '2026-04-22 14:30:00.000', '2026-04-22 14:30:00.000',
                    [], [], [], {})
                """).formatted(id, externalId, type, dataSetId));
    }

    /**
     * A retriever filtering on the given dataset ids; {@code null} means "no dataset filter".
     *
     * <p>Ids only: by the time a filter reaches this layer, {@code EventService} has already
     * resolved any reference given by externalId (it owns the node tables, this layer does not).
     */
    private static EventRetreiver filteringOn(List<Long> dataSetIds) {
        EventFilter filter = new EventFilter();
        filter.setDataSetId(dataSetIds == null ? null
                : dataSetIds.stream().map(IdCollection::createFromId).toList());
        EventRetreiver retreiver = new EventRetreiver();
        retreiver.setFilter(filter);
        return retreiver;
    }

    private static Set<String> idsOf(List<EventModel> events) {
        return events.stream().map(EventModel::getId).collect(Collectors.toSet());
    }

    // ---- drill-down (filter) --------------------------------------------------------------------

    @Test
    void filtersByOneDataSetId() {
        assertEquals(Set.of(ALARM_12.toString(), CALIB_12.toString()),
                idsOf(service.filter(filteringOn(List.of(12L)))));
    }

    @Test
    void filtersBySeveralDataSetIds() {
        assertEquals(Set.of(ALARM_12.toString(), CALIB_12.toString(), ALARM_34.toString()),
                idsOf(service.filter(filteringOn(List.of(12L, 34L)))));
    }

    /** Empty is "match nothing" — and specifically NOT "return everything". */
    @Test
    void emptyDataSetIdsMatchesNothing() {
        assertTrue(service.filter(filteringOn(List.of())).isEmpty());
    }

    /** Null drops the predicate entirely, rather than becoming {@code IN []}. */
    @Test
    void nullDataSetIdsMatchesEverything() {
        assertEquals(4, service.filter(filteringOn(null)).size());
    }

    @Test
    void dataSetIdsAndsWithOtherPredicates() {
        EventRetreiver retreiver = filteringOn(List.of(12L));
        retreiver.getFilter().setType(List.of("alarm"));

        assertEquals(Set.of(ALARM_12.toString()), idsOf(service.filter(retreiver)));
    }

    /**
     * The user's filter and the dataset ACL are separate AND-terms, so the filter can only ever
     * narrow within the ACL — never reach past it.
     */
    @Test
    void dataSetIdsCannotWidenPastTheDatasetAcl() {
        assertEquals(Set.of(ALARM_34.toString()),
                idsOf(service.filter(filteringOn(List.of(12L, 34L)), List.of(34L))));
    }

    // ---- aggregate ------------------------------------------------------------------------------

    @Test
    void aggregateHonoursDataSetIdsFilter() {
        List<EventQueryResult.Bucket> buckets =
                service.aggregate(filteringOn(List.of(12L)), "dataSetId", null);

        assertEquals(1, buckets.size());
        assertEquals("12", buckets.getFirst().value());
        assertEquals(2L, buckets.getFirst().count());
    }

    @Test
    void aggregateGroupsAcrossSeveralDataSets() {
        List<EventQueryResult.Bucket> buckets =
                service.aggregate(filteringOn(List.of(12L, 56L)), "type", null);

        assertEquals(Set.of("alarm", "calibration"),
                buckets.stream().map(EventQueryResult.Bucket::value).collect(Collectors.toSet()));
        assertEquals(3L, buckets.stream().mapToLong(EventQueryResult.Bucket::count).sum());
    }

    /** The empty guard must hold on the aggregate path too — it builds its WHERE separately. */
    @Test
    void aggregateWithEmptyDataSetIdsReturnsNoBuckets() {
        assertTrue(service.aggregate(filteringOn(List.of()), "type", null).isEmpty());
    }

    @Test
    void aggregateWithNullDataSetIdsCountsEverything() {
        assertEquals(4L, service.aggregate(filteringOn(null), "type", null)
                .stream().mapToLong(EventQueryResult.Bucket::count).sum());
    }
}
