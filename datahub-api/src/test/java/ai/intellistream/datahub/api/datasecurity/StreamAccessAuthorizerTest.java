// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.datasecurity;

import ai.intellistream.datahub.jpa.domains.TimeseriesEntity;
import ai.intellistream.datahub.repositories.node.TimeseriesRepository;
import ai.intellistream.datahub.repositories.subscription.SubscriptionRepository;
import ai.intellistream.datahub.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link StreamAccessAuthorizer} — the dataset-ACL policy the datapoint-streaming
 * WebSocket handlers apply off the request thread. Covers both the granted and the denied paths.
 */
class StreamAccessAuthorizerTest {

    private final TimeseriesRepository timeseriesRepository = mock(TimeseriesRepository.class);
    private final SubscriptionRepository subscriptionRepository = mock(SubscriptionRepository.class);
    private final DatasetPermissionsResolver permissionsResolver = mock(DatasetPermissionsResolver.class);
    private final StreamAccessAuthorizer authorizer =
            new StreamAccessAuthorizer(timeseriesRepository, subscriptionRepository, permissionsResolver);

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    private static Jwt jwtWithRealmRoles(String... roles) {
        return Jwt.withTokenValue("t")
                .header("alg", "none")
                .claim("realm_access", Map.of("roles", Arrays.asList(roles)))
                .build();
    }

    /** The all-datasets read grant (the /datasets/*&#47;read wildcard group, already resolved). */
    private static DatasetPermissions readAllPerms() {
        return DatasetPermissions.of(true, false, Set.of(), Set.of());
    }

    private static DatasetPermissions permsForDatasets(Set<Long> readableIds) {
        return DatasetPermissions.of(false, false, readableIds, Set.of());
    }

    // ---- role gate ----------------------------------------------------------------------------

    @Test
    void hasAccessRoleReflectsRealmRoles() {
        assertThat(StreamAccessAuthorizer.hasAccessRole(jwtWithRealmRoles("DATAHUB_ACCESS"))).isTrue();
        assertThat(StreamAccessAuthorizer.hasAccessRole(jwtWithRealmRoles("SOMETHING_ELSE"))).isFalse();
        assertThat(StreamAccessAuthorizer.hasAccessRole(Jwt.withTokenValue("t").header("alg", "none")
                .claim("sub", "x").build())).isFalse();
    }

    /**
     * permissionsOf delegates to the resolver, and must scope TenantContext around it: these run on
     * container threads where no filter chain has set a tenant, and grants resolve per tenant.
     */
    @Test
    void permissionsOfResolvesUnderTheGivenTenant() {
        Jwt jwt = jwtWithRealmRoles("DATAHUB_ACCESS");
        AtomicReference<String> tenantDuringResolve = new AtomicReference<>();
        when(permissionsResolver.forJwt(jwt)).thenAnswer(inv -> {
            tenantDuringResolve.set(TenantContext.getTenantId());
            return permsForDatasets(Set.of(9L));
        });

        DatasetPermissions perms = authorizer.permissionsOf("tenant-a", jwt);

        assertThat(perms.canRead(9)).isTrue();
        assertThat(tenantDuringResolve.get()).isEqualTo("tenant-a");
        // ...and restored afterwards, so the container thread is left as it was found.
        assertThat(TenantContext.getTenantId()).isNull();
    }

    @Test
    void permissionsOfPrincipalDelegatesToTheResolver() {
        Jwt jwt = jwtWithRealmRoles("DATAHUB_ACCESS");
        var principal = new JwtAuthenticationToken(jwt, List.of());
        when(permissionsResolver.forPrincipal(principal)).thenReturn(permsForDatasets(Set.of(3L)));

        DatasetPermissions perms = authorizer.permissionsOf("tenant-a", principal);

        assertThat(perms.canRead(3)).isTrue();
        assertThat(perms.canRead(4)).isFalse();
    }

    /** A previously-set tenant is restored rather than cleared. */
    @Test
    void permissionsOfRestoresAPreExistingTenant() {
        TenantContext.setTenantId("tenant-outer");
        Jwt jwt = jwtWithRealmRoles("DATAHUB_ACCESS");
        when(permissionsResolver.forJwt(jwt)).thenReturn(DatasetPermissions.none());

        authorizer.permissionsOf("tenant-inner", jwt);

        assertThat(TenantContext.getTenantId()).isEqualTo("tenant-outer");
    }

    // ---- live-tail interest filtering ---------------------------------------------------------

    @Test
    void readAllKeepsEveryRequestedExternalIdWithoutQuerying() {
        List<String> result = authorizer.readableExternalIds(
                "tenant-a", readAllPerms(), List.of("ts-a", "ts-b"));
        assertThat(result).containsExactly("ts-a", "ts-b");
        verifyNoInteractions(timeseriesRepository);
    }

    @Test
    void noReadableDatasetsDropsEveryRequestedExternalId() {
        List<String> result = authorizer.readableExternalIds(
                "tenant-a", DatasetPermissions.none(), List.of("ts-a", "ts-b"));
        assertThat(result).isEmpty();
        verifyNoInteractions(timeseriesRepository);
    }

    @Test
    void emptyRequestReturnsEmpty() {
        assertThat(authorizer.readableExternalIds("tenant-a", readAllPerms(), List.of()))
                .isEmpty();
        verifyNoInteractions(timeseriesRepository);
    }

    @Test
    void specificReadRoleKeepsOnlyTheReadableTimeseries() {
        // The caller may read dataset 5; the repo (which filters by readable dataset in SQL) returns
        // only ts-a, so the unreadable ts-secret is dropped from the interest set.
        TimeseriesEntity readable = mock(TimeseriesEntity.class);
        when(readable.getExternalId()).thenReturn("ts-a");
        when(timeseriesRepository.findAllByIdOrExternalIdAndDataSetIdIn(any(), any(), any()))
                .thenReturn(List.of(readable));

        List<String> result = authorizer.readableExternalIds(
                "tenant-a", permsForDatasets(Set.of(5L)), List.of("ts-a", "ts-secret"));

        assertThat(result).containsExactly("ts-a");
        assertThat(result).doesNotContain("ts-secret");
    }

    @Test
    void interestFilteringRestoresTenantContext() {
        authorizer.readableExternalIds("tenant-a", readAllPerms(), List.of("ts-a"));
        assertThat(TenantContext.getTenantId()).isNull();
    }

    // ---- subscription attach authorization ----------------------------------------------------

    @Test
    void readAllMayAttachAnySubscriptionWithoutQuerying() {
        assertThat(authorizer.canReadSubscription("tenant-a", readAllPerms(), "sub-x"))
                .isTrue();
        verify(subscriptionRepository, never()).findTimeseriesDatasetIds(anyLong());
    }

    @Test
    void mayAttachWhenEveryBoundTimeseriesDatasetIsReadable() {
        when(subscriptionRepository.findTimeseriesDatasetIds(anyLong())).thenReturn(List.of(5L, 6L));
        assertThat(authorizer.canReadSubscription(
                "tenant-a", permsForDatasets(Set.of(5L, 6L)), "sub-x")).isTrue();
    }

    @Test
    void deniedWhenAnyBoundTimeseriesDatasetIsUnreadable() {
        when(subscriptionRepository.findTimeseriesDatasetIds(anyLong())).thenReturn(List.of(5L, 6L));
        assertThat(authorizer.canReadSubscription(
                "tenant-a", permsForDatasets(Set.of(5L)), "sub-x")).isFalse();
    }

    @Test
    void deniedWhenSubscriptionBindsAnOrphanTimeseriesAndCallerIsNotReadAll() {
        List<Long> withOrphan = new ArrayList<>();
        withOrphan.add(5L);
        withOrphan.add(null); // orphan timeseries (no dataset)
        when(subscriptionRepository.findTimeseriesDatasetIds(anyLong())).thenReturn(withOrphan);
        assertThat(authorizer.canReadSubscription(
                "tenant-a", permsForDatasets(Set.of(5L)), "sub-x")).isFalse();
    }

    @Test
    void emptySubscriptionIsTriviallyPermitted() {
        when(subscriptionRepository.findTimeseriesDatasetIds(anyLong())).thenReturn(List.of());
        assertThat(authorizer.canReadSubscription(
                "tenant-a", permsForDatasets(Set.of(5L)), "sub-empty")).isTrue();
    }
}
