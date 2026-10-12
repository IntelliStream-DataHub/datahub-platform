// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.clickhouse;

import ai.intellistream.datahub.models.EventModel;
import ai.intellistream.datahub.models.events.EventFilter;
import ai.intellistream.datahub.models.paging.PageCursor;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link ClickHouseEventService#latestPerExternalId} against a real ClickHouse server: the latest
 * event of each lifecycle, kept by that latest event's status.
 *
 * <p>Run with {@code ./gradlew :datahub-infra:integrationTest} on a host with Docker/Podman.
 */
@Tag("integration")
class ClickHouseLatestEventsIT {

    private static final String TENANT = "default";

    static Client client;
    static ClickHouseEventService service;
    static int seq;

    // Lifecycles, all of type "flow" unless noted. Times are minutes past 2026-04-22 10:00.
    static UUID closedResolved;   // open(0) -> resolved(10): closed
    static UUID reopened;         // open(0) -> resolved(10) -> open(20): open again
    static UUID splitAcrossSets;  // open(5) in ds 12 -> resolved(15) in ds 34
    static UUID splitResolved;    //   ... the resolved event, the one in ds 34
    static UUID stillOpen;        // open(30), inserted twice as an unmerged duplicate
    static UUID otherType;        // open(40), type "alarm"

    @BeforeAll
    static void setUp() throws Exception {
        client = SharedClickHouse.newClient("latest_events_it");
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
        // Separate inserts are separate parts; with merges stopped, a re-sent row stays a
        // duplicate the query has to collapse itself.
        SharedClickHouse.execute(client, "SYSTEM STOP MERGES events");

        insert("closed_resolved", "flow", "open", 0, 12);
        closedResolved = insert("closed_resolved", "flow", "resolved", 10, 12);

        insert("reopened", "flow", "open", 0, 12);
        insert("reopened", "flow", "resolved", 10, 12);
        reopened = insert("reopened", "flow", "open", 20, 12);

        splitAcrossSets = insert("split", "flow", "open", 5, 12);
        splitResolved = insert("split", "flow", "resolved", 15, 34);

        stillOpen = insert("still_open", "flow", "open", 30, 12);
        insertWithId(stillOpen, "still_open", "flow", "open", 30, 12);

        otherType = insert("other_type", "alarm", "open", 40, 12);

        TenantConfigService tenantConfigService = mock(TenantConfigService.class);
        ClickHouseClientPool pool = mock(ClickHouseClientPool.class);
        Tenant tenant = new Tenant();
        tenant.setOrganizationId(TENANT);
        when(tenantConfigService.getConfig(anyString())).thenReturn(tenant);
        when(pool.getClient(any(Tenant.class))).thenReturn(client);
        when(pool.getReadOnlyClient(any(Tenant.class))).thenReturn(client);
        service = new ClickHouseEventService(tenantConfigService, mock(ValkeyService.class), pool);
        TenantContext.setTenantId(TENANT);
    }

    @AfterAll
    static void tearDown() {
        TenantContext.clear();
        if (client != null) client.close();
    }

    private static UUID insert(String externalId, String type, String status, int minute, long dataSetId)
            throws Exception {
        // Version-7-shaped and increasing, so ids sort in insertion order like real ones.
        UUID id = UUID.fromString("0193a4b5-6c7d-7e8f-9012-%012d".formatted(++seq));
        insertWithId(id, externalId, type, status, minute, dataSetId);
        return id;
    }

    private static void insertWithId(UUID id, String externalId, String type, String status, int minute,
                                     long dataSetId) throws Exception {
        String time = "2026-04-22 10:%02d:00.000".formatted(minute);
        SharedClickHouse.execute(client, ("""
                INSERT INTO events (id, external_id, external_id_hash, type, sub_type, status, description,
                    data_set_id, source, date_created, last_updated, event_time,
                    related_resources_id, related_resources_external_id, related_resources_external_id_hash, metadata)
                VALUES ('%s', '%s', 0, '%s', 'run-failed', '%s', 'desc', %d, 'flow',
                    '%s', '%s', '%s', [], [], [], {})
                """).formatted(id, externalId, type, status, dataSetId, time, time, time));
    }

    private static EventFilter ofType(String type) {
        EventFilter filter = new EventFilter();
        filter.setType(List.of(type));
        return filter;
    }

    private static List<UUID> ids(List<EventModel> events) {
        return events.stream().map(e -> UUID.fromString(e.getId())).toList();
    }

    @Test
    void keepsLifecyclesWhoseLatestEventIsOpen_newestFirst() {
        List<EventModel> open = service.latestPerExternalId(ofType("flow"), List.of("open"), 100, null, null);

        // closed_resolved is out, and so is split: its latest is resolved. still_open comes once,
        // though it is stored twice; reopened comes as its third event, not its first.
        assertEquals(List.of(stillOpen, reopened), ids(open));
        assertEquals("open", open.get(1).getStatus());
    }

    @Test
    void withoutStatus_returnsEveryLifecyclesLatestEvent() {
        List<EventModel> latest = service.latestPerExternalId(ofType("flow"), null, 100, null, null);

        assertEquals(List.of(stillOpen, reopened, splitResolved, closedResolved), ids(latest));
    }

    @Test
    void theLatestEventIsTheLatestTheCallerCanSee() {
        // Only data set 12 readable: the resolved event in 34 is invisible, so split's latest
        // visible event is its open one.
        List<EventModel> open = service.latestPerExternalId(ofType("flow"), List.of("open"), 100, null,
                Set.of(12L));

        assertEquals(List.of(stillOpen, reopened, splitAcrossSets), ids(open));
    }

    @Test
    void statusIsMatchedLikeTheFilter_caseInsensitiveWithWildcards() {
        assertEquals(List.of(stillOpen, reopened),
                ids(service.latestPerExternalId(ofType("flow"), List.of("OP*"), 100, null, null)));
    }

    @Test
    void filterNarrowsTheCandidates() {
        assertEquals(List.of(otherType),
                ids(service.latestPerExternalId(ofType("alarm"), List.of("open"), 100, null, null)));
    }

    @Test
    void pagesThroughEveryLifecycleOnceWithTheCursor() {
        List<UUID> walked = new ArrayList<>();
        PageCursor after = null;
        for (int page = 0; page < 10; page++) {
            List<EventModel> events = service.latestPerExternalId(new EventFilter(), null, 2, after, null);
            walked.addAll(ids(events));
            if (events.size() < 2) {
                break;
            }
            EventModel last = events.getLast();
            after = new PageCursor("eventTime", true,
                    String.valueOf(last.getEventTime().toInstant().toEpochMilli()), last.getId());
        }

        assertEquals(List.of(otherType, stillOpen, reopened, splitResolved, closedResolved), walked);
    }
}
