// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.clickhouse;

import ai.intellistream.datahub.helpers.utils.IdGenerator;
import ai.intellistream.datahub.tenant.Tenant;
import ai.intellistream.datahub.models.events.EventFilter;
import ai.intellistream.datahub.models.DataSort;
import ai.intellistream.datahub.models.paging.MalformedCursorException;
import ai.intellistream.datahub.models.paging.PageCursor;
import ai.intellistream.datahub.models.EventModel;
import ai.intellistream.datahub.models.events.EventRetreiver;
import ai.intellistream.datahub.tenant.TenantConfigService;
import ai.intellistream.datahub.services.ValkeyService;
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
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The {@code externalId} and {@code source} pattern lists, against a real ClickHouse.
 *
 * <p>ClickHouse is a second implementation of matching rules the node filters express in Criteria —
 * events are not nodes, so {@code EventFilter} cannot share {@code NodePredicateBuilder}. The
 * promises therefore have to be checked twice, and this is the second place: {@code *} and
 * {@code %} both mean "any run", an underscore is literal, and a literal entry resolves through
 * {@code external_id_hash} rather than a scan.
 *
 * <p>Separate from {@code ClickHouseEventFilterIT} on purpose. That class asserts absolute counts
 * over the whole table ("null dataSetIds matches everything"), so rows inserted for these cases
 * would break it. A different database name gives these their own {@code events} table.
 *
 * <p>Run with {@code ./gradlew :datahub-infra:integrationTest} on a host with Docker/Podman.
 */
@Tag("integration")
class ClickHouseEventPatternFilterIT {

    private static final String TENANT = "default";

    private static final UUID WO_EXACT = UUID.fromString("0193c1d2-0001-7000-8000-000000000001");
    private static final UUID WO_SIBLING = UUID.fromString("0193c1d2-0001-7000-8000-000000000002");
    private static final UUID SHIFT_REAL = UUID.fromString("0193c1d2-0001-7000-8000-000000000003");
    private static final UUID SHIFT_DECOY = UUID.fromString("0193c1d2-0001-7000-8000-000000000004");
    private static final UUID FROM_OPC = UUID.fromString("0193c1d2-0001-7000-8000-000000000005");

    static Client client;
    static ClickHouseEventService service;

    @BeforeAll
    static void setUp() throws Exception {
        client = SharedClickHouse.newClient("event_pattern_filter_it");

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

        // shift_report_1 and shiftxreport_1 differ only where an unescaped LIKE underscore would
        // match either — the pair that makes the escaping visible.
        insert(WO_EXACT, "work_order_4711", "sap", "alarm", "electrical", "OPEN");
        insert(WO_SIBLING, "work_order_4712", "sap", "warning", "electrical", "OPEN");
        insert(SHIFT_REAL, "shift_report_1", "SAP", "maint_planned", "mechanical", "CLOSED");
        insert(SHIFT_DECOY, "shiftxreport_1", "sap", "maint_urgent", "mechanical", "CLOSED");
        insert(FROM_OPC, "alarm_9000", "opcua_north", "alarm", "process", "IN_PROGRESS");

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

    /**
     * Stores {@code external_id_hash} exactly as the production writers do — BLAKE3 over
     * {@code externalId + tenant}, via {@link IdGenerator#generate128bitKey}.
     *
     * <p>This fixture used to call {@code ExternalIds.hash}, the <em>node</em> hash, which is what
     * let a real bug pass: the filter read the column with the same wrong function, so writer and
     * reader agreed with each other and disagreed with production. A fixture that computes a stored
     * value itself has to compute it the way the system does, or it tests only its own arithmetic.
     */
    private static void insert(UUID id, String externalId, String source) throws Exception {
        insert(id, externalId, source, "alarm", "routine", "open");
    }

    private static void insert(UUID id, String externalId, String source,
                               String type, String subType, String status) throws Exception {
        insert(id, externalId, source, type, subType, status, "2026-04-22 14:30:00.000");
    }

    private static void insert(UUID id, String externalId, String source,
                               String type, String subType, String status, String createdAt) throws Exception {
        // try-with-resources: QueryResponse holds a pooled connection, and the client's pool is
        // small. Leaking one per insert was survivable while every insert happened in @BeforeAll;
        // adding a few more from inside test methods exhausted it, and the symptom was a 40s
        // connection timeout several tests later rather than an error at the leak.
        SharedClickHouse.execute(client, ("""
                INSERT INTO events (id, external_id, external_id_hash, type, sub_type, status, description,
                    data_set_id, source, date_created, last_updated, event_time,
                    related_resources_id, related_resources_external_id, related_resources_external_id_hash, metadata)
                VALUES ('%s', '%s', %d, '%s', %s, '%s', 'desc', 12, '%s',
                    '%s', '%s', '%s',
                    [], [], [], {})
                """).formatted(id, externalId, IdGenerator.generate128bitKey(externalId, TENANT), type, subType == null ? "NULL" : "'" + subType + "'", status, source,
                        createdAt, createdAt, createdAt));
    }

    private static EventRetreiver matching(List<String> externalIds, List<String> sources) {
        EventFilter filter = new EventFilter();
        filter.setExternalId(externalIds);
        filter.setSource(sources);
        EventRetreiver retreiver = new EventRetreiver();
        retreiver.setFilter(filter);
        return retreiver;
    }

    private static Set<String> idsOf(List<EventModel> events) {
        return events.stream().map(EventModel::getId).collect(Collectors.toSet());
    }

    /** The exact entry goes through external_id_hash, the pattern through ILIKE; they OR together. */
    @Test
    void externalIdsMixExactAndPattern() {
        assertEquals(Set.of(WO_EXACT.toString(), WO_SIBLING.toString()),
                idsOf(service.filter(matching(List.of("work_order_4711", "work_order_471*"), null))));
    }

    @Test
    void externalIdsExactEntryMatchesOnlyItself() {
        assertEquals(Set.of(WO_EXACT.toString()),
                idsOf(service.filter(matching(List.of("work_order_4711"), null))));
    }

    @Test
    void bothWildcardSpellingsAgree() {
        assertEquals(idsOf(service.filter(matching(List.of("work_order_47%"), null))),
                idsOf(service.filter(matching(List.of("work_order_47*"), null))));
    }

    @Test
    void leadingWildcardMatchesSuffix() {
        assertEquals(Set.of(WO_EXACT.toString()),
                idsOf(service.filter(matching(List.of("*_4711"), null))));
    }

    /** Without escaping, this pattern would also return shiftxreport_1. */
    @Test
    void underscoreIsLiteralInsideAPattern() {
        assertEquals(Set.of(SHIFT_REAL.toString()),
                idsOf(service.filter(matching(List.of("shift_report*"), null))));
    }

    /**
     * Exact matching on events is case-<em>sensitive</em>, and this pins that rather than wishing
     * otherwise. Events hash their external id verbatim in every writer, so the stored key for
     * {@code shift_report_1} is not the key for {@code SHIFT_REPORT_1}. Nodes differ — they
     * lowercase before hashing — which is a real inconsistency in the platform, not something this
     * filter can paper over: matching case-insensitively here would mean not using the stored hash
     * at all.
     */
    @Test
    void exactExternalIdMatchingIsCaseSensitive() {
        assertEquals(Set.of(), idsOf(service.filter(matching(List.of("SHIFT_REPORT_1"), null))));
        assertEquals(Set.of(SHIFT_REAL.toString()),
                idsOf(service.filter(matching(List.of("shift_report_1"), null))));
    }

    /** A wildcard entry goes through ILIKE instead, which <em>is</em> case-insensitive. */
    @Test
    void wildcardExternalIdMatchingIsCaseInsensitive() {
        assertEquals(Set.of(SHIFT_REAL.toString()),
                idsOf(service.filter(matching(List.of("SHIFT_REPORT_1*"), null))));
    }

    @Test
    void sourcesArePatternListAndCaseInsensitive() {
        assertEquals(Set.of(WO_EXACT.toString(), WO_SIBLING.toString(), SHIFT_REAL.toString(),
                        SHIFT_DECOY.toString(), FROM_OPC.toString()),
                idsOf(service.filter(matching(null, List.of("sap", "opcua_*")))));
    }

    @Test
    void sourcesNarrowAlongsideExternalIds() {
        assertEquals(Set.of(FROM_OPC.toString()),
                idsOf(service.filter(matching(List.of("alarm_*"), List.of("opcua_*")))));
    }

    // ---- type / subType / status as pattern lists ------------------------------------------------
    // These were single exact strings while the rest of the filter took lists, so the most-used
    // event criteria were the least capable ones.

    private static EventRetreiver ofTypes(List<String> types, List<String> subTypes, List<String> statuses) {
        EventFilter filter = new EventFilter();
        filter.setType(types);
        filter.setSubType(subTypes);
        filter.setStatus(statuses);
        EventRetreiver retreiver = new EventRetreiver();
        retreiver.setFilter(filter);
        return retreiver;
    }

    /** The call that used to take two: alarms and warnings at once. */
    @Test
    void typesMatchAnyOfSeveral() {
        assertEquals(Set.of(WO_EXACT.toString(), WO_SIBLING.toString(), FROM_OPC.toString()),
                idsOf(service.filter(ofTypes(List.of("alarm", "warning"), null, null))));
    }

    @Test
    void typesAcceptWildcards() {
        assertEquals(Set.of(SHIFT_REAL.toString(), SHIFT_DECOY.toString()),
                idsOf(service.filter(ofTypes(List.of("maint_*"), null, null))));
    }

    /** The underscore in maint_planned is literal here too, so this matches one row, not two. */
    @Test
    void typeUnderscoresAreLiteral() {
        assertEquals(Set.of(SHIFT_REAL.toString()),
                idsOf(service.filter(ofTypes(List.of("maint_planned"), null, null))));
    }

    @Test
    void statusesMatchAnyOfSeveral() {
        assertEquals(Set.of(WO_EXACT.toString(), WO_SIBLING.toString(), FROM_OPC.toString()),
                idsOf(service.filter(ofTypes(null, null, List.of("OPEN", "IN_PROGRESS")))));
    }

    @Test
    void typesSubTypesAndStatusesAndTogether() {
        assertEquals(Set.of(WO_EXACT.toString()),
                idsOf(service.filter(ofTypes(List.of("alarm"), List.of("electrical"), List.of("OPEN")))));
    }

    /** Metadata's key-only meaning is the one the node filters adopted; it still holds here. */
    @Test
    void typesAreCaseInsensitive() {
        assertEquals(Set.of(WO_EXACT.toString(), FROM_OPC.toString()),
                idsOf(service.filter(ofTypes(List.of("ALARM"), null, null))));
    }

    // ---- default ordering ------------------------------------------------------------------------
    // These insert their own rows, so they use a type/status/source no other test in this class
    // filters on. The table is shared across the class and several assertions above are exact sets;
    // a fixture that matched one of their filters would fail it from a distance.

    /**
     * By event_time, then id. What matters is that the order is defined at all: this path emitted
     * no ORDER BY, so a limited query returned an arbitrary subset and two identical requests could
     * disagree about which rows those were. Inserted out of order so a pass cannot come from
     * ClickHouse happening to return insertion order.
     */
    @Test
    void resultsComeBackInEventTimeOrderByDefault() throws Exception {
        UUID first = UUID.fromString("0193c1d2-0002-7000-8000-000000000001");
        UUID second = UUID.fromString("0193c1d2-0002-7000-8000-000000000002");
        UUID third = UUID.fromString("0193c1d2-0002-7000-8000-000000000003");
        insert(third, "ordered_evt_3", "ordering_src", "ordering", "ordering", "NA", "2026-03-01 00:00:00.000");
        insert(first, "ordered_evt_1", "ordering_src", "ordering", "ordering", "NA", "2026-01-01 00:00:00.000");
        insert(second, "ordered_evt_2", "ordering_src", "ordering", "ordering", "NA", "2026-02-01 00:00:00.000");

        List<String> ids = service.filter(matching(List.of("ordered_evt_*"), null)).stream()
                .map(EventModel::getId)
                .toList();

        assertEquals(List.of(first.toString(), second.toString(), third.toString()), ids);
    }

    /**
     * Events sharing an event_time still come back in a fixed order — id makes it total. Without a
     * tiebreaker the order within a timestamp is ClickHouse's choice, which is the same
     * non-determinism in a smaller window; events arriving in the same millisecond is exactly the
     * case the cursor was built for.
     */
    @Test
    void idBreaksTiesSoTheOrderIsTotal() throws Exception {
        UUID lower = UUID.fromString("0193c1d2-0004-7000-8000-00000000000a");
        UUID higher = UUID.fromString("0193c1d2-0004-7000-8000-00000000000b");
        insert(higher, "tied_evt_2", "ordering_src", "ordering", "ordering", "NA", "2026-05-05 12:00:00.000");
        insert(lower, "tied_evt_1", "ordering_src", "ordering", "ordering", "NA", "2026-05-05 12:00:00.000");

        List<String> first = service.filter(matching(List.of("tied_evt_*"), null)).stream()
                .map(EventModel::getId).toList();
        List<String> again = service.filter(matching(List.of("tied_evt_*"), null)).stream()
                .map(EventModel::getId).toList();

        assertEquals(List.of(lower.toString(), higher.toString()), first);
        assertEquals(first, again);
    }

    /** An unsortable property is dropped, and the caller still gets an order rather than none. */
    @Test
    void anUnsortablePropertyFallsBackToTheDefault() throws Exception {
        UUID older = UUID.fromString("0193c1d2-0003-7000-8000-000000000001");
        UUID newer = UUID.fromString("0193c1d2-0003-7000-8000-000000000002");
        insert(older, "fallback_evt_1", "ordering_src", "ordering", "ordering", "NA", "2026-01-01 00:00:00.000");
        insert(newer, "fallback_evt_2", "ordering_src", "ordering", "ordering", "NA", "2026-02-01 00:00:00.000");

        EventRetreiver retreiver = matching(List.of("fallback_evt_*"), null);
        DataSort sort = new DataSort();
        sort.setProperty(List.of("noSuchColumn"));
        retreiver.setSort(sort);

        List<String> ids = service.filter(retreiver).stream().map(EventModel::getId).toList();

        assertEquals(List.of(older.toString(), newer.toString()), ids);
    }

    // ---- keyset paging ---------------------------------------------------------------------------
    // The point of a cursor over OFFSET: each page is a range scan the index can seek to, and no
    // row is skipped or repeated when the boundary falls inside a run of equal values. These walk a
    // set small enough to check exhaustively, which is the only way to see a boundary bug.

    private static EventRetreiver page(String prefix, String cursor, int limit, String property, boolean desc) {
        EventFilter filter = new EventFilter();
        // Each paging test owns a prefix: the table is shared across the class, so a shared one
        // would have every test walking the others' rows.
        filter.setExternalId(List.of(prefix + "*"));
        EventRetreiver retreiver = new EventRetreiver();
        retreiver.setFilter(filter);
        retreiver.setLimit(limit);
        retreiver.setCursor(cursor);
        if (property != null) {
            DataSort sort = new DataSort();
            sort.setProperty(List.of(property));
            sort.setOrder(desc ? "desc" : "asc");
            retreiver.setSort(sort);
        }
        return retreiver;
    }

    /** Walks the whole set two at a time and asserts it comes back exactly once, in order. */
    private List<String> walk(String prefix, String property, boolean desc, int pageSize, int expectedTotal) {
        List<String> seen = new ArrayList<>();
        String cursor = null;
        for (int guard = 0; guard <= expectedTotal + 2; guard++) {
            List<EventModel> events = service.filter(page(prefix, cursor, pageSize, property, desc));
            if (events.isEmpty()) {
                break;
            }
            events.forEach(e -> seen.add(e.getId()));
            if (events.size() < pageSize) {
                break;
            }
            EventModel last = events.get(events.size() - 1);
            // Epoch millis, not toString(): the getters return ZonedDateTime over a Long field,
            // and the query layer parses the boundary back out as millis.
            String value = switch (property == null ? "eventTime" : property) {
                case "createdTime" -> String.valueOf(last.getCreatedTime().toInstant().toEpochMilli());
                case "externalId" -> last.getExternalId();
                case "subType" -> last.getSubType();   // may be null: that is the point
                default -> String.valueOf(last.getEventTime().toInstant().toEpochMilli());
            };
            cursor = new PageCursor(property == null ? "eventTime" : property, desc, value, last.getId()).encode();
        }
        return seen;
    }

    @Test
    void pagingReturnsEveryRowExactlyOnce() throws Exception {
        for (int i = 1; i <= 5; i++) {
            insert(UUID.fromString("0193c1d2-0005-7000-8000-00000000000" + i),
                    "pagewalk_evt_" + i, "paging_src", "paging", "paging", "NA",
                    "2026-06-0%d 00:00:00.000".formatted(i));
        }

        List<String> everything = service.filter(page("pagewalk_evt_", null, 100, null, false)).stream()
                .map(EventModel::getId).toList();
        List<String> paged = walk("pagewalk_evt_", null, false, 2, 5);

        assertEquals(5, everything.size());
        assertEquals(everything, paged, "paging two at a time must reproduce the single-page result");
    }

    /**
     * Rows sharing a sort value are where keyset pagination goes wrong: without the id
     * tie-breaker the boundary either repeats them or drops them.
     */
    @Test
    void tiedSortValuesAreNeitherSkippedNorRepeated() throws Exception {
        for (int i = 1; i <= 4; i++) {
            insert(UUID.fromString("0193c1d2-0006-7000-8000-00000000000" + i),
                    "pagetie_evt_" + i, "paging_src", "paging", "paging", "NA",
                    "2026-07-01 00:00:00.000");   // all four share an event_time
        }

        List<String> everything = service.filter(page("pagetie_evt_", null, 100, null, false)).stream()
                .map(EventModel::getId).toList();
        List<String> paged = walk("pagetie_evt_", null, false, 2, everything.size());

        assertEquals(4, everything.size());
        assertEquals(everything, paged);
        assertEquals(everything.size(), paged.stream().distinct().count(), "no row twice");
    }

    @Test
    void pagingWorksDescendingToo() throws Exception {
        for (int i = 1; i <= 3; i++) {
            insert(UUID.fromString("0193c1d2-0007-7000-8000-00000000000" + i),
                    "pagedesc_evt_" + i, "paging_src", "paging", "paging", "NA",
                    "2026-08-0%d 00:00:00.000".formatted(i));
        }

        List<String> everything = service.filter(page("pagedesc_evt_", null, 100, "eventTime", true)).stream()
                .map(EventModel::getId).toList();
        List<String> paged = walk("pagedesc_evt_", "eventTime", true, 2, everything.size());

        assertEquals(everything, paged);
    }

    @Test
    void pagingWorksOnANonDefaultSortColumn() throws Exception {
        for (int i = 1; i <= 3; i++) {
            insert(UUID.fromString("0193c1d2-0008-7000-8000-00000000000" + i),
                    "pageext_evt_" + i, "paging_src", "paging", "paging", "NA",
                    "2026-09-0%d 00:00:00.000".formatted(i));
        }

        List<String> everything = service.filter(page("pageext_evt_", null, 100, "externalId", false)).stream()
                .map(EventModel::getId).toList();
        List<String> paged = walk("pageext_evt_", "externalId", false, 2, everything.size());

        assertEquals(everything, paged);
    }


    /**
     * The exact-match path, which resolves through the indexed hash rather than a scan. Guards the
     * mismatch that shipped: the filter hashed with the node algorithm (xxHash3, 64-bit, unsalted)
     * while every event writer uses BLAKE3 over externalId+tenant at 128 bits, so an exact filter
     * matched nothing while looking perfectly well-formed.
     */
    @Test
    void exactExternalIdMatchesTheHashTheWritersProduce() {
        assertEquals(Set.of(WO_EXACT.toString()),
                idsOf(service.filter(matching(List.of("work_order_4711"), null))));
    }

    /** Several exact ids resolve through one IN over the hash column. */
    @Test
    void severalExactExternalIdsMatchTogether() {
        assertEquals(Set.of(WO_EXACT.toString(), WO_SIBLING.toString()),
                idsOf(service.filter(matching(List.of("work_order_4711", "work_order_4712"), null))));
    }

    // ---- injection --------------------------------------------------------------------------------
    // This query is assembled by string concatenation, unlike the Criteria-built node filters, so
    // the separation between structure and value is a property of the code rather than of a
    // framework. Column names come from fixed maps; every caller value is a named parameter. These
    // assert that holds for each field a caller controls.

    @Test
    void injectionPayloadsAreDataInEveryPatternField() {
        for (String payload : List.of(
                "' OR 1=1 --",
                "'; DROP TABLE events; --",
                "%' OR '1'='1",
                "{malicious:String}")) {
            assertEquals(Set.of(), idsOf(service.filter(matching(List.of(payload), null))),
                    "externalIds payload: " + payload);
            assertEquals(Set.of(), idsOf(service.filter(matching(null, List.of(payload)))),
                    "sources payload: " + payload);
            assertEquals(Set.of(), idsOf(service.filter(ofTypes(List.of(payload), null, null))),
                    "types payload: " + payload);
        }

        // The table survived, and normal filtering still works.
        assertEquals(Set.of(WO_EXACT.toString()),
                idsOf(service.filter(matching(List.of("work_order_4711"), null))));
    }

    /**
     * A ClickHouse parameter placeholder inside a value must not be re-read as a placeholder.
     * {@code {x:String}} is the client's own binding syntax, so a value containing it is the case
     * where "bound, not concatenated" either holds or does not.
     */
    @Test
    void aParameterPlaceholderInsideAValueIsNotExpanded() {
        assertEquals(Set.of(), idsOf(service.filter(matching(List.of("{ext_p0:String}"), null))));
    }

    /** Metadata keys and values are caller-controlled and reach the query as parameters too. */
    @Test
    void injectionInMetadataIsData() {
        EventFilter filter = new EventFilter();
        filter.setMetadata(java.util.Collections.singletonMap("' OR 1=1 --", "'; DROP TABLE events; --"));
        EventRetreiver retreiver = new EventRetreiver();
        retreiver.setFilter(filter);

        assertEquals(Set.of(), idsOf(service.filter(retreiver)));
    }

    /**
     * The cursor's boundary is caller-supplied and lands in a comparison, so a payload there is
     * compared as text like any other value — a quote sorts before any letter, so both work_order
     * rows legitimately follow it. What matters is that it is a comparison, not a fragment of SQL.
     */
    @Test
    void injectionInsideACursorIsComparedAsText() {
        EventRetreiver retreiver = matching(List.of("work_order_*"), null);
        DataSort sort = new DataSort();
        sort.setProperty(List.of("externalId"));
        retreiver.setSort(sort);
        retreiver.setCursor(new PageCursor("externalId", false, "' OR 1=1 --", WO_EXACT.toString()).encode());

        assertEquals(Set.of(WO_EXACT.toString(), WO_SIBLING.toString()), idsOf(service.filter(retreiver)));
    }

    /**
     * A boundary that cannot be read as the sorted column's type fails loudly. It used to reach
     * Long.parseLong and surface as a 500 from a value the caller supplied; ignoring it instead
     * would hand a paging client the first page forever.
     */
    @Test
    void anUnreadableCursorBoundaryIsRejected() {
        EventRetreiver retreiver = matching(List.of("work_order_*"), null);
        retreiver.setCursor(new PageCursor("eventTime", false, "' OR 1=1 --", WO_EXACT.toString()).encode());

        assertThrows(MalformedCursorException.class, () -> service.filter(retreiver));
    }

    /**
     * Sorting by a nullable event column must still page. subType and status are Nullable in
     * ClickHouse, and a walk over them used to stop after one page: no cursor was produced, so a
     * client reading "no nextCursor" as "done" silently lost every row after the first page.
     */
    @Test
    void pagingWorksOnANullableSortColumn() throws Exception {
        for (int i = 1; i <= 4; i++) {
            insert(UUID.fromString("0193c1d2-0009-7000-8000-00000000000" + i),
                    "pagenull_evt_" + i, "paging_src", "paging",
                    i % 2 == 0 ? null : "sub_" + i, "NA",
                    "2026-10-0%d 00:00:00.000".formatted(i));
        }

        List<String> everything = service.filter(page("pagenull_evt_", null, 100, "subType", false))
                .stream().map(EventModel::getId).toList();
        List<String> paged = walk("pagenull_evt_", "subType", false, 2, everything.size());

        assertEquals(4, everything.size());
        assertEquals(everything, paged);
    }

    /**
     * The cursor format changed: it was {@code <epochMillis>_<uuid>}, it is now an opaque encoded
     * token. A cursor issued by the old build is therefore not readable by this one — and is
     * rejected rather than misread. Worth pinning because the previous behaviour for an unreadable
     * cursor was to ignore it and return the first page, so a client mid-walk across a deploy would
     * have silently restarted and re-delivered everything it had already seen.
     */
    @Test
    void aCursorInTheOldFormatIsRejectedRatherThanMisread() {
        EventRetreiver retreiver = matching(List.of("work_order_*"), null);
        retreiver.setCursor("1754476522104_" + WO_EXACT);

        assertThrows(MalformedCursorException.class, () -> service.filter(retreiver));
    }
}
