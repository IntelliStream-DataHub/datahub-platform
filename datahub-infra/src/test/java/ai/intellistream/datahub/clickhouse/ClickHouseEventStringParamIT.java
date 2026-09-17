// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.clickhouse;

import ai.intellistream.datahub.helpers.utils.IdGenerator;
import ai.intellistream.datahub.models.EventModel;
import ai.intellistream.datahub.models.UpdateEventForm;
import ai.intellistream.datahub.models.events.EventFilter;
import ai.intellistream.datahub.models.events.EventRetreiver;
import ai.intellistream.datahub.models.validation.EventFields;
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

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Backslashes, TABs and newlines in values bound to {@code {name:String}} parameters.
 *
 * <p>ClickHouse reads those parameters in its escaped text format, so an unescaped value lost a
 * level of backslashes: a type filter ending in {@code \} failed the query, {@code a\\b} matched a
 * stored {@code a\b}, and an update to {@code C:\temp} stored a TAB. Every path that binds one is
 * exercised here — pattern lists, metadata, the advanced filter, search and update.
 */
@Tag("integration")
class ClickHouseEventStringParamIT {

    private static final String TENANT = "default";

    private static final UUID PIPE = UUID.fromString("0193e1f2-0001-7000-8000-000000000001");
    private static final UUID TRAILING = UUID.fromString("0193e1f2-0001-7000-8000-000000000002");
    private static final UUID EDITED = UUID.fromString("0193e1f2-0001-7000-8000-000000000003");

    private static final String PIPE_TYPE = "pipe\\type";
    private static final String PIPE_DESCRIPTION = "C:\\temp\\new";
    private static final String PIPE_PATH = "C:\\temp";
    private static final String TRAILING_TYPE = "ends\\";

    static Client client;
    static ClickHouseEventService service;

    @BeforeAll
    static void setUp() {
        client = SharedClickHouse.newClient("event_string_param_it");
        SharedClickHouse.execute(client, """
                CREATE TABLE events (
                    id UUID, external_id LowCardinality(String), external_id_hash Int128,
                    type LowCardinality(String), sub_type Nullable(String), status Nullable(String),
                    description String, data_set_id Int64, source LowCardinality(String),
                    date_created DateTime64(3, 'UTC'), last_updated DateTime64(3, 'UTC'),
                    event_time DateTime64(3, 'UTC'),
                    related_resources_id Array(Int64),
                    related_resources_external_id Array(LowCardinality(String)),
                    related_resources_external_id_hash Array(Int64),
                    metadata Map(LowCardinality(String), String)
                ) ENGINE = ReplacingMergeTree ORDER BY id PARTITION BY (toYYYYMM(event_time))
                """);

        seed(PIPE, "str_param_pipe", PIPE_TYPE, PIPE_DESCRIPTION, "path", PIPE_PATH);
        seed(TRAILING, "str_param_trailing", TRAILING_TYPE, "plain", "path", "plain");
        seed(EDITED, "str_param_edited", "edited", "before", "path", "plain");

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

    private static void seed(UUID id, String externalId, String type, String description,
                             String metadataKey, String metadataValue) {
        SharedClickHouse.execute(client, ("""
                INSERT INTO events (id, external_id, external_id_hash, type, sub_type, status,
                    description, data_set_id, source, date_created, last_updated, event_time,
                    related_resources_id, related_resources_external_id,
                    related_resources_external_id_hash, metadata)
                VALUES ('%s', '%s', %s, %s, NULL, 'OPEN', %s, 12, 'sensor',
                    '2026-04-22 14:30:00.000', '2026-04-22 14:30:00.000', '2026-04-22 14:30:00.000',
                    [], [], [], {%s:%s})
                """).formatted(id, externalId, IdGenerator.generate128bitKeySigned(externalId, TENANT),
                sqlLiteral(type), sqlLiteral(description), sqlLiteral(metadataKey), sqlLiteral(metadataValue)));
    }

    private static String sqlLiteral(String value) {
        return "'" + value.replace("\\", "\\\\").replace("'", "\\'") + "'";
    }

    private static String column(String column, UUID id) {
        try (var response = client.query(
                "SELECT any(" + column + ") AS v FROM events WHERE id = '" + id + "'").get()) {
            var reader = client.newBinaryFormatReader(response);
            return reader.next() == null ? null : reader.getString("v");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static Set<String> idsOf(List<EventModel> events) {
        return events.stream().map(EventModel::getId).collect(Collectors.toSet());
    }

    private static Set<String> filterBy(EventFilter filter, String advancedFilter) {
        EventRetreiver retreiver = new EventRetreiver();
        retreiver.setFilter(filter);
        retreiver.setAdvancedFilter(advancedFilter);
        return idsOf(service.filter(retreiver));
    }

    private static Set<String> ofTypes(String... types) {
        EventFilter filter = new EventFilter();
        filter.setType(List.of(types));
        return filterBy(filter, null);
    }

    /** Guards the fixture: a seed that stored the wrong bytes would make every test below vacuous. */
    @Test
    void theFixturesHoldSingleBackslashes() {
        assertEquals(PIPE_TYPE, column("type", PIPE));
        assertEquals(PIPE_DESCRIPTION, column("description", PIPE));
        assertEquals(TRAILING_TYPE, column("type", TRAILING));
    }

    @Test
    void aTypeEndingInABackslashMatchesItself() {
        assertEquals(Set.of(TRAILING.toString()), ofTypes(TRAILING_TYPE));
    }

    @Test
    void aBackslashInATypeMatchesOnlyABackslash() {
        assertEquals(Set.of(PIPE.toString()), ofTypes(PIPE_TYPE));
        assertEquals(Set.of(), ofTypes("pipe\\\\type"));
    }

    @Test
    void aMetadataValueWithABackslashMatches() {
        EventFilter filter = new EventFilter();
        filter.setMetadata(Map.of("path", PIPE_PATH));
        assertEquals(Set.of(PIPE.toString()), filterBy(filter, null));
    }

    @Test
    void theAdvancedFilterComparesBackslashesLiterally() {
        assertEquals(Set.of(PIPE.toString()), filterBy(new EventFilter(), "description = 'C:\\temp\\new'"));
    }

    @Test
    void searchMatchesTextContainingABackslash() {
        assertEquals(Set.of(PIPE.toString()), idsOf(service.search(PIPE_PATH, 10, null, null)));
    }

    @Test
    void anUpdateStoresBackslashesTabsAndNewlinesVerbatim() {
        String value = "D:\\edited\\x\tsecond column\nsecond line\\";
        EventFields fields = new EventFields();
        fields.getDescription().set(value);
        EventCudMessage message = new EventCudMessage();
        message.setTenantId(TENANT);
        message.setUpdateEvents(List.of(new UpdateEventForm().setExternalId("str_param_edited").setUpdate(fields)));

        service.updateEvents(message);

        awaitEquals(value, () -> column("description", EDITED));
    }

    /** ALTER UPDATE is a mutation and lands asynchronously. */
    private static void awaitEquals(String expected, Supplier<String> actual) {
        String seen = null;
        for (int i = 0; i < 100; i++) {
            seen = actual.get();
            if (expected.equals(seen)) {
                return;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        assertEquals(expected, seen);
    }
}
