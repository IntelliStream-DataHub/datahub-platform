// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.flow.store;

import ai.intellistream.datahub.tenant.Tenant;
import ai.intellistream.datahub.tenant.TenantConfigService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Tenants configured onto one KVRocks each see only their own keys. */
@Tag("integration")
class TenantKvrocksIT {

    @Test
    void tenantsOnOneKvrocksKeepTheirKeysApart() {
        SharedKvrocks.flush();
        TenantConfigService tenants = mock(TenantConfigService.class);
        for (String id : new String[]{"tenant-a", "tenant-b"}) {
            Tenant tenant = new Tenant();
            tenant.setOrganizationId(id);
            tenant.setKvrocksTenant(SharedKvrocks.kvrocks(0));
            when(tenants.getConfig(id)).thenReturn(tenant);
        }
        try (TenantKvrocks kvrocks = new TenantKvrocks(tenants)) {
            kvrocks.connection("tenant-a").sync().set("flow:seq", "1");

            assertThat(kvrocks.connection("tenant-b").sync().get("flow:seq")).isNull();
            assertThat(kvrocks.<String>dedicated("tenant-a", c -> c.sync().get("flow:seq"))).isEqualTo("1");
            assertThat(kvrocks.connection("tenant-a").sync().keys("{tenant-a}:*")).containsExactly("flow:seq");
            assertThat(kvrocks.connection("tenant-b").sync().keys("{tenant-b}:*")).isEmpty();
        }
    }
}
