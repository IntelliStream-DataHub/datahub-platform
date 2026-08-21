// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.clickhouse;

import ai.intellistream.datahub.helpers.utils.IdGenerator;
import ai.intellistream.datahub.models.EventModel;
import ai.intellistream.datahub.models.UpdateEventForm;
import ai.intellistream.datahub.pulsar.EventCudMessage;
import ai.intellistream.datahub.services.ValkeyService;
import ai.intellistream.datahub.tenant.Tenant;
import ai.intellistream.datahub.tenant.TenantConfigService;
import ai.intellistream.datahub.tenant.TenantContext;
import ai.intellistream.datahub.testsupport.SharedClickHouse;
import com.clickhouse.client.api.Client;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Update and delete by externalId must find rows whose key exceeds 2^127.
 *
 * <p>{@code generate128bitKey} is unsigned — 128 random bits read as positive — while
 * {@code events.external_id_hash} is {@code Int128}, which is signed. The insert path writes the
 * raw low 128 bits, so any key with its top bit set is stored as its negative two's-complement
 * twin. Binding the unsigned form matched none of those: roughly half of all external ids,
 * silently, and always the same ones, so it looked like a problem with particular events.
 *
 * <p>Each test uses both an id whose key has the top bit set and one whose key does not, so a
 * regression shows up as "half the ids work" rather than as a total failure.
 */
@Tag("integration")
class ClickHouseEventSignedHashIT {

    private static final String TENANT = "default";

    static Client client;
    static ClickHouseEventService service;

    @BeforeAll
    static void setUp() throws Exception {
        client = SharedClickHouse.newClient("event_signed_hash_it");
        execute("""
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

        TenantConfigService tenantConfigService = mock(TenantConfigService.class);
        ClickHouseClientPool pool = mock(ClickHouseClientPool.class);
        Tenant tenant = new Tenant();
        tenant.setOrganizationId(TENANT);
        when(tenantConfigService.getConfig(anyString())).thenReturn(tenant);
        when(pool.getClient(any(Tenant.class))).thenReturn(client);

        service = new ClickHouseEventService(tenantConfigService, mock(ValkeyService.class), pool);
        TenantContext.setTenantId(TENANT);
    }

    @AfterAll
    static void tearDown() {
        TenantContext.clear();
        if (client != null) client.close();
    }

    /** Run a statement and release its response; the pooled connection leaks otherwise. */
    private static void execute(String sql) {
        try (var ignored = client.query(sql).get()) {
            // DDL and INSERT return nothing worth reading.
        } catch (Exception e) {
            throw new IllegalStateException("ClickHouse statement failed: " + sql, e);
        }
    }

    /** An external id whose key has its top bit set — the half that used to be unreachable. */
    private static final String TOP_BIT = "evt_1";
    /** One whose key does not — the half that worked either way. */
    private static final String PLAIN = "evt_5";

    /** Seeds a row exactly as the insert path stores it: the raw low 128 bits, i.e. signed. */
    private static void seed(UUID id, String externalId) {
        BigInteger stored = IdGenerator.generate128bitKeySigned(externalId, TENANT);
        execute(("""
                INSERT INTO events (id, external_id, external_id_hash, type, sub_type, status,
                    description, data_set_id, source, date_created, last_updated, event_time,
                    related_resources_id, related_resources_external_id,
                    related_resources_external_id_hash, metadata)
                VALUES ('%s', '%s', %s, 'alarm', NULL, 'OPEN', 'seeded', 12, 'sensor',
                    '2026-04-22 14:30:00.000', '2026-04-22 14:30:00.000', '2026-04-22 14:30:00.000',
                    [], [], [], {})
                """).formatted(id, externalId, stored));
    }

    private static String statusOf(String externalId) {
        try (var r = client.query(
                "SELECT any(status) AS s FROM events WHERE external_id = '" + externalId + "'").get()) {
            var reader = client.newBinaryFormatReader(r);
            return reader.next() == null ? null : reader.getString("s");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }


    private static EventCudMessage messageFor(UpdateEventForm... forms) {
        EventCudMessage m = new EventCudMessage();
        m.setTenantId(TENANT);
        m.setUpdateEvents(List.of(forms));
        return m;
    }

    // No delete case here: EventService.delete resolves external ids to UUIDs through KVRocks
    // before publishing, so ClickHouse only ever deletes by id and never binds this hash.

    @Test
    @DisplayName("the two fixtures really do straddle 2^127")
    void theFixturesCoverBothHalves() {
        assertTrue(IdGenerator.generate128bitKey(TOP_BIT, TENANT).testBit(127),
                TOP_BIT + " should hash above 2^127 — pick another id if the tenant changed");
        assertTrue(!IdGenerator.generate128bitKey(PLAIN, TENANT).testBit(127),
                PLAIN + " should hash below 2^127");
        assertTrue(IdGenerator.generate128bitKeySigned(TOP_BIT, TENANT).signum() < 0,
                "the stored form of " + TOP_BIT + " is negative");
    }

    @Test
    @DisplayName("update by externalId reaches a row whose key is above 2^127")
    void updateByExternalIdFindsTheSignedRow() {
        UUID top = UUID.fromString("0193d1e2-0001-7000-8000-000000000001");
        UUID plain = UUID.fromString("0193d1e2-0001-7000-8000-000000000002");
        seed(top, TOP_BIT);
        seed(plain, PLAIN);

        service.updateEvents(messageFor(
                new UpdateEventForm().setExternalId(TOP_BIT).setUpdate(statusPatch("CLOSED")),
                new UpdateEventForm().setExternalId(PLAIN).setUpdate(statusPatch("CLOSED"))));

        // ALTER UPDATE is a mutation and lands asynchronously.
        awaitEquals("CLOSED", () -> statusOf(TOP_BIT),
                "the row above 2^127 was not updated — the unsigned bind misses exactly these");
        awaitEquals("CLOSED", () -> statusOf(PLAIN), "the row below 2^127 was not updated");
    }

    /** ClickHouse applies mutations asynchronously, so the assertion has to wait for one. */
    private static void awaitEquals(String expected, java.util.function.Supplier<String> actual, String message) {
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
        assertEquals(expected, seen, message);
    }


    private static ai.intellistream.datahub.models.validation.EventFields statusPatch(String status) {
        var fields = new ai.intellistream.datahub.models.validation.EventFields();
        fields.getStatus().set(status);
        return fields;
    }
}
