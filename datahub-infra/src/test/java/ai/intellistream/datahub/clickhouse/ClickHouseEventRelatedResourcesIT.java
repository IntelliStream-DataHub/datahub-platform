// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.clickhouse;

import ai.intellistream.datahub.helpers.text.ExternalIds;
import ai.intellistream.datahub.models.EventModel;
import ai.intellistream.datahub.models.IdCollection;
import ai.intellistream.datahub.models.UpdateEventForm;
import ai.intellistream.datahub.models.events.EventFilter;
import ai.intellistream.datahub.models.events.EventRetreiver;
import ai.intellistream.datahub.pulsar.EventCudMessage;
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
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Round-trip coverage for an event's related resources against a real ClickHouse server.
 *
 * <p>The API exposes one {@code List<IdCollection> relatedResources}; the table stores three
 * parallel arrays derived from it. These tests are what make that denormalization safe: a
 * write-then-read must return exactly what went in, a mutation must move all three columns
 * together, and a row written before the single-list model (whose arrays can be misaligned) must
 * still read back without loss.
 *
 * <p>Run with {@code ./gradlew :datahub-infra:integrationTest} on a host with Docker/Podman.
 */
@Tag("integration")
@Testcontainers
class ClickHouseEventRelatedResourcesIT {

    private static final String TENANT = "default";

    private static final long PUMP_ID = 7L;
    private static final String PUMP_EXT = "pump_7";
    private static final long SENSOR_ID = 34L;
    private static final String SENSOR_EXT = "sensor_abc";

    @Container
    static final ClickHouseContainer CLICKHOUSE =
            new ClickHouseContainer(DockerImageName.parse("clickhouse/clickhouse-server:24.8"))
                    .withUsername("tester")
                    .withPassword("test")
                    .waitingFor(Wait.forHttp("/ping").forPort(8123).forStatusCode(200));

    static Client client;
    static ClickHouseEventService service;

    @BeforeAll
    static void setUp() throws Exception {
        client = new Client.Builder()
                .addEndpoint("http://" + CLICKHOUSE.getHost() + ":" + CLICKHOUSE.getMappedPort(8123))
                .setUsername(CLICKHOUSE.getUsername())
                .setPassword(CLICKHOUSE.getPassword())
                .setDefaultDatabase("default")
                .build();

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
                    metadata                             Map(LowCardinality(String), String)
                ) ENGINE = ReplacingMergeTree
                  ORDER BY id
                  PARTITION BY (toYYYYMM(event_time))
                """);

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

    private static IdCollection related(Long id, String externalId) {
        IdCollection entry = new IdCollection();
        entry.setId(id);
        entry.setExternalId(externalId);
        return entry;
    }

    /** Writes one event through the production RowBinary insert path and returns its id. */
    private static UUID createEvent(String externalId, List<IdCollection> relatedResources) {
        EventModel event = new EventModel();
        UUID id = UUID.randomUUID();
        event.setId(id.toString());
        event.setExternalId(externalId);
        event.setType("alarm");
        event.setStatus("OPEN");
        event.setDescription("d");
        event.setSource("sensor");
        event.setDataSetId(12L);
        event.setEventTime(ZonedDateTime.parse("2026-04-22T14:30:00Z"));
        event.setCreatedTime(ZonedDateTime.parse("2026-04-22T14:30:00Z"));
        event.setLastUpdatedTime(ZonedDateTime.parse("2026-04-22T14:30:00Z"));
        event.setRelatedResources(new ArrayList<>(relatedResources));

        EventCudMessage message = new EventCudMessage();
        message.setTenantId(TENANT);
        message.setEvents(new ArrayList<>(List.of(event)));
        service.createEvents(message);
        return id;
    }

    private static EventModel read(UUID id) {
        return service.findById(id.toString()).getItems().iterator().next();
    }

    /** Reads the raw columns so a test can assert all three moved together. */
    private static List<String> rawColumns(UUID id) throws Exception {
        var records = client.queryAll(
                "SELECT toString(related_resources_id) AS ids, toString(related_resources_external_id) AS exts, "
                + "toString(related_resources_external_id_hash) AS hashes FROM events FINAL WHERE id = '" + id + "'");
        var record = records.iterator().next();
        return List.of(record.getString("ids"), record.getString("exts"), record.getString("hashes"));
    }

    @Test
    void writeThenReadReturnsExactlyWhatWentIn() {
        // The test that makes drift structurally impossible: one list in, the same list out.
        UUID id = createEvent("rr_roundtrip", List.of(
                related(PUMP_ID, PUMP_EXT), related(SENSOR_ID, SENSOR_EXT)));

        EventModel event = read(id);

        assertEquals(List.of(PUMP_ID, SENSOR_ID),
                event.getRelatedResources().stream().map(IdCollection::getId).toList());
        assertEquals(List.of(PUMP_EXT, SENSOR_EXT),
                event.getRelatedResources().stream().map(IdCollection::getExternalId).toList());
    }

    @Test
    void writeDerivesAllThreeColumnsIncludingTheHashArray() throws Exception {
        UUID id = createEvent("rr_columns", List.of(related(PUMP_ID, PUMP_EXT)));

        List<String> columns = rawColumns(id);

        assertEquals("[" + PUMP_ID + "]", columns.get(0));
        assertEquals("['" + PUMP_EXT + "']", columns.get(1));
        assertEquals("[" + ExternalIds.hash(PUMP_EXT) + "]", columns.get(2));
    }

    @Test
    void emptyRelatedResourcesRoundTripsAsEmpty() {
        UUID id = createEvent("rr_empty", List.of());

        assertTrue(read(id).getRelatedResources().isEmpty());
    }

    @Test
    void statusIsMappedOnRead() {
        // The status column was selected but never mapped, so every read returned null and the
        // console badge was always blank.
        UUID id = createEvent("rr_status", List.of());

        assertEquals("OPEN", read(id).getStatus());
    }

    @Test
    void misalignedLegacyRowStillReadsBackWithoutLoss() throws Exception {
        // Rows written before the single-list model back-filled each array independently, so the
        // two can differ in length and ordering. They must degrade to single-sided entries rather
        // than being dropped or blowing up on an index.
        UUID id = UUID.randomUUID();
        SharedClickHouse.execute(client, ("""
                INSERT INTO events (id, external_id, external_id_hash, type, sub_type, status, description,
                    data_set_id, source, date_created, last_updated, event_time,
                    related_resources_id, related_resources_external_id, related_resources_external_id_hash, metadata)
                VALUES ('%s', 'rr_legacy', 0, 'alarm', NULL, 'OPEN', 'd', 12, 'sensor',
                    '2026-04-22 14:30:00.000', '2026-04-22 14:30:00.000', '2026-04-22 14:30:00.000',
                    [%d, 99], ['%s'], [%d], {})
                """).formatted(id, PUMP_ID, PUMP_EXT, ExternalIds.hash(PUMP_EXT)));

        EventModel event = read(id);

        assertEquals(2, event.getRelatedResources().size());
        assertEquals(PUMP_ID, event.getRelatedResources().get(0).getId());
        assertEquals(PUMP_EXT, event.getRelatedResources().get(0).getExternalId());
        assertEquals(99L, event.getRelatedResources().get(1).getId());
        assertNull(event.getRelatedResources().get(1).getExternalId(), "the unpaired id has no external id");
    }

    @Test
    void mutationMovesAllThreeColumnsTogether() throws Exception {
        // The headline drift bug: the mutation used to be built from the raw patch form, so a
        // patch touching only the external-id side left related_resources_id stale.
        UUID id = createEvent("rr_mutate", List.of(related(PUMP_ID, PUMP_EXT)));

        EventModel resolved = read(id);
        resolved.setRelatedResources(new ArrayList<>(List.of(related(SENSOR_ID, SENSOR_EXT))));

        EventCudMessage message = new EventCudMessage();
        message.setTenantId(TENANT);
        message.setEvents(new ArrayList<>(List.of(resolved)));
        UpdateEventForm form = new UpdateEventForm().setId(id);
        form.getUpdate().getRelatedResources().set(List.of(related(SENSOR_ID, SENSOR_EXT)));
        message.setUpdateEvents(new ArrayList<>(List.of(form)));

        service.updateEvents(message);
        waitForMutations();

        List<String> columns = rawColumns(id);
        assertEquals("[" + SENSOR_ID + "]", columns.get(0), "the id column must not be left stale");
        assertEquals("['" + SENSOR_EXT + "']", columns.get(1));
        assertEquals("[" + ExternalIds.hash(SENSOR_EXT) + "]", columns.get(2));
    }

    @Test
    void mutationLeavesRelatedResourcesAloneWhenNotMentioned() throws Exception {
        UUID id = createEvent("rr_untouched", List.of(related(PUMP_ID, PUMP_EXT)));

        EventCudMessage message = new EventCudMessage();
        message.setTenantId(TENANT);
        message.setEvents(new ArrayList<>(List.of(read(id))));
        UpdateEventForm form = new UpdateEventForm().setId(id);
        form.getUpdate().getDescription().set("a new description");
        message.setUpdateEvents(new ArrayList<>(List.of(form)));

        service.updateEvents(message);
        waitForMutations();

        assertEquals("[" + PUMP_ID + "]", rawColumns(id).get(0));
    }

    @Test
    void filterByResourceId() {
        UUID match = createEvent("rr_filter_id", List.of(related(PUMP_ID, PUMP_EXT)));
        UUID other = createEvent("rr_filter_id_other", List.of(related(SENSOR_ID, SENSOR_EXT)));

        Set<String> hits = filterIds(IdCollection.createFromId(PUMP_ID));

        assertTrue(hits.contains(match.toString()));
        assertFalse(hits.contains(other.toString()), "an event attached elsewhere must not match");
    }

    @Test
    void filterByResourceExternalId() {
        UUID match = createEvent("rr_filter_ext", List.of(related(SENSOR_ID, SENSOR_EXT)));

        assertTrue(filterIds(IdCollection.createFromExternalId(SENSOR_EXT)).contains(match.toString()));
    }

    @Test
    void filterByBothSidesRequiresAllOfThem() {
        // hasAll semantics: an event attached to only one of the two must not match.
        UUID both = createEvent("rr_filter_both", List.of(
                related(PUMP_ID, PUMP_EXT), related(SENSOR_ID, SENSOR_EXT)));
        UUID onlyPump = createEvent("rr_filter_one", List.of(related(PUMP_ID, PUMP_EXT)));

        Set<String> hits = filterIds(
                IdCollection.createFromId(PUMP_ID), IdCollection.createFromExternalId(SENSOR_EXT));

        assertTrue(hits.contains(both.toString()));
        assertFalse(hits.contains(onlyPump.toString()), "an event missing one of the resources must not match");
    }

    private static Set<String> filterIds(IdCollection... relatedResources) {
        EventFilter filter = new EventFilter();
        filter.setRelatedResources(new ArrayList<>(List.of(relatedResources)));
        EventRetreiver retreiver = new EventRetreiver();
        retreiver.setFilter(filter);
        retreiver.setLimit(100);
        return service.filter(retreiver).stream().map(EventModel::getId).collect(Collectors.toSet());
    }

    /** ALTER TABLE ... UPDATE is asynchronous; block until the queue drains. */
    private static void waitForMutations() throws Exception {
        for (int i = 0; i < 100; i++) {
            var records = client.queryAll(
                    "SELECT count() AS pending FROM system.mutations WHERE table = 'events' AND is_done = 0");
            if (records.iterator().next().getLong("pending") == 0) {
                return;
            }
            Thread.sleep(100);
        }
        throw new IllegalStateException("ClickHouse mutations did not finish in time");
    }
}
