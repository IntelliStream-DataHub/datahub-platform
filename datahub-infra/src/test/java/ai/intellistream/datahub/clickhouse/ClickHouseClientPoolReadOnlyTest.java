// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.clickhouse;

import ai.intellistream.datahub.tenant.ClickHouseTenant;
import ai.intellistream.datahub.tenant.Tenant;
import com.clickhouse.client.api.Client;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Which credentials a read goes out on.
 *
 * <p>Clients are built lazily and never connect here — what is under test is the pool's choice,
 * not ClickHouse. The choice is worth pinning because both of its wrong answers are silent: hand
 * back the owner when a reader exists and the privilege separation is gone with nothing to show
 * for it; hand back a reader that does not exist and every existing tenant's event filtering
 * starts failing authentication.
 */
class ClickHouseClientPoolReadOnlyTest {

    private final ClickHouseClientPool pool = new ClickHouseClientPool();

    @AfterEach
    void closeClients() {
        pool.closeAll();
    }

    private static Tenant tenant(String readOnlyUser) {
        ClickHouseTenant ch = new ClickHouseTenant();
        ch.setHost("ch-1.example.com");
        ch.setDatabaseName("acme");
        ch.setUsername("acme_owner");
        ch.setPassword("owner-secret");
        ch.setReadOnlyUsername(readOnlyUser);
        ch.setReadOnlyPassword(readOnlyUser == null ? null : "reader-secret");
        Tenant t = new Tenant();
        t.setOrganizationId("acme");
        t.setClickHouseTenant(ch);
        return t;
    }

    @Test
    void aTenantWithAReaderGetsASecondClientForIt() {
        Tenant t = tenant("acme_reader");

        assertThat(pool.getReadOnlyClient(t)).isNotSameAs(pool.getClient(t));
    }

    /**
     * A tenant provisioned before the reader existed shares the one client rather than opening a
     * second identical one — and, more to the point, keeps working at all.
     */
    @Test
    void aTenantWithoutAReaderFallsBackToTheOwnerClient() {
        Tenant t = tenant(null);

        assertThat(pool.getReadOnlyClient(t)).isSameAs(pool.getClient(t));
    }

    @Test
    void bothClientsAreCachedPerTenant() {
        Tenant t = tenant("acme_reader");

        assertThat(pool.getClient(t)).isSameAs(pool.getClient(t));
        assertThat(pool.getReadOnlyClient(t)).isSameAs(pool.getReadOnlyClient(t));
    }

    /** A rotated reader password must rebuild, or the pool serves credentials Vault has replaced. */
    @Test
    void rotatingTheReaderPasswordRebuildsOnlyTheReadOnlyClient() {
        Tenant t = tenant("acme_reader");
        Client owner = pool.getClient(t);
        Client reader = pool.getReadOnlyClient(t);

        t.getClickHouseTenant().setReadOnlyPassword("rotated");

        assertThat(pool.getReadOnlyClient(t)).isNotSameAs(reader);
        assertThat(pool.getClient(t)).isSameAs(owner);
    }

    @Test
    void invalidateDropsBothClients() {
        Tenant t = tenant("acme_reader");
        Client owner = pool.getClient(t);
        Client reader = pool.getReadOnlyClient(t);

        pool.invalidate("acme");

        assertThat(pool.getClient(t)).isNotSameAs(owner);
        assertThat(pool.getReadOnlyClient(t)).isNotSameAs(reader);
    }
}
