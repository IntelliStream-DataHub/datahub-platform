// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.subscription;

import ai.intellistream.datahub.jpa.domains.SubscriptionEntity;
import ai.intellistream.datahub.jpa.domains.TimeseriesEntity;
import ai.intellistream.datahub.repositories.subscription.SubscriptionRepository;
import ai.intellistream.datahub.tenant.Tenant;
import ai.intellistream.datahub.tenant.TenantConfigService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SubscriptionCacheTest {

    private final SubscriptionRepository repo = mock(SubscriptionRepository.class);
    private final TenantConfigService tenantConfig = mock(TenantConfigService.class);
    private final SubscriptionCache cache = new SubscriptionCache(repo, tenantConfig);

    private static Tenant tenant(String id) {
        Tenant t = mock(Tenant.class);
        when(t.getOrganizationId()).thenReturn(id);
        return t;
    }

    private static TimeseriesEntity ts(long id) {
        TimeseriesEntity ts = mock(TimeseriesEntity.class);
        when(ts.getId()).thenReturn(id);
        return ts;
    }

    private static SubscriptionEntity sub(String externalId, TimeseriesEntity... timeseries) {
        SubscriptionEntity s = mock(SubscriptionEntity.class);
        when(s.getExternalId()).thenReturn(externalId);
        when(s.getTimeseries()).thenReturn(new java.util.LinkedHashSet<>(List.of(timeseries)));
        return s;
    }

    @Test
    void loadAllPopulatesBindingsPerTenant() {
        // cachedTenants is a public field on TenantConfigService — assign it directly on the mock.
        tenantConfig.cachedTenants = new ConcurrentHashMap<>(Map.of("t1", tenant("t1")));
        // Build the entity mocks (which themselves stub with when()) BEFORE stubbing findAll, so the
        // nested stubbing doesn't land inside when(repo.findAll()).
        List<SubscriptionEntity> subs = List.of(
                sub("sub-a", ts(10L), ts(11L)),
                sub("sub-b", ts(11L)));
        when(repo.findAll()).thenReturn(subs);

        cache.loadAll();

        assertEquals(Set.of("sub-a"), cache.getSubscriptionExternalIds("t1", 10L));
        assertEquals(Set.of("sub-a", "sub-b"), cache.getSubscriptionExternalIds("t1", 11L));
        assertTrue(cache.getSubscriptionExternalIds("t1", 99L).isEmpty());
        // Unknown tenant → empty, never null.
        assertTrue(cache.getSubscriptionExternalIds("other", 10L).isEmpty());
    }

    @Test
    void addIsIdempotentAndTenantScoped() {
        cache.add("t1", 5L, "sub-x");
        cache.add("t1", 5L, "sub-x"); // duplicate — no effect
        cache.add("t2", 5L, "sub-y");

        assertEquals(Set.of("sub-x"), cache.getSubscriptionExternalIds("t1", 5L));
        assertEquals(Set.of("sub-y"), cache.getSubscriptionExternalIds("t2", 5L));
    }

    @Test
    void removePrunesEmptyBucketsAndIsSafeForAbsentBindings() {
        cache.add("t1", 5L, "sub-x");
        cache.add("t1", 5L, "sub-z");

        cache.remove("t1", 5L, "sub-x");
        assertEquals(Set.of("sub-z"), cache.getSubscriptionExternalIds("t1", 5L));

        cache.remove("t1", 5L, "sub-z");   // last one → bucket pruned
        assertTrue(cache.getSubscriptionExternalIds("t1", 5L).isEmpty());

        // Removing a binding that was never present must not throw.
        cache.remove("t1", 5L, "never-there");
        cache.remove("no-tenant", 1L, "nope");
    }
}
