// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.datasecurity;

import ai.intellistream.datahub.helpers.text.ExternalIds;
import ai.intellistream.datahub.jpa.domains.TimeseriesEntity;
import ai.intellistream.datahub.repositories.node.TimeseriesRepository;
import ai.intellistream.datahub.repositories.subscription.SubscriptionRepository;
import ai.intellistream.datahub.tenant.TenantContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;

import java.security.Principal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Dataset-ACL authorization for the datapoint-streaming WebSocket handlers.
 *
 * <p>{@link DataSecurity} derives the caller's dataset roles from the {@code SecurityContextHolder},
 * which is populated only on the servlet request thread. The WebSocket handlers run their
 * per-connection and per-batch logic on container / receive-loop threads where that context is
 * absent, so this service derives the same {@link DatasetPermissions} directly from the connection's
 * JWT — from the decoded token for the query-param-authenticated live tail, or from the handshake
 * principal for the header-authenticated subscription socket — and enforces the identical dataset
 * read rules the REST read paths apply.
 */
@Service
@Slf4j
public class StreamAccessAuthorizer {

    /** Baseline realm role every authenticated caller must hold (mirrors {@code SecurityConfig}). */
    public static final String REQUIRED_ACCESS_ROLE = "DATAHUB_ACCESS";

    private final TimeseriesRepository timeseriesRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final DatasetPermissionsResolver permissionsResolver;

    public StreamAccessAuthorizer(TimeseriesRepository timeseriesRepository,
                                  SubscriptionRepository subscriptionRepository,
                                  DatasetPermissionsResolver permissionsResolver) {
        this.timeseriesRepository = timeseriesRepository;
        this.subscriptionRepository = subscriptionRepository;
        this.permissionsResolver = permissionsResolver;
    }

    /** The realm roles carried in a decoded token's {@code realm_access.roles} claim. */
    public static List<String> realmRoles(Jwt jwt) {
        Map<String, Object> realmAccess = jwt.getClaimAsMap("realm_access");
        if (realmAccess != null && realmAccess.get("roles") instanceof List<?> roles) {
            return roles.stream().filter(Objects::nonNull).map(Object::toString).toList();
        }
        return List.of();
    }

    /** True if the decoded token carries the baseline {@link #REQUIRED_ACCESS_ROLE}. */
    public static boolean hasAccessRole(Jwt jwt) {
        return realmRoles(jwt).contains(REQUIRED_ACCESS_ROLE);
    }

    /**
     * Dataset permissions from a decoded token, for the live-tail path where no security filter
     * chain has run.
     *
     * <p>Grants now come from Keycloak organization groups rather than from the token, so resolving
     * them reaches Keycloak/Valkey. Resolve <strong>per connection or per control message</strong>,
     * never per datapoint batch. The tenant is passed explicitly, like everything else here: these
     * run on container threads where {@code TenantContext} is not set, and the closure lookup is
     * per-tenant.
     */
    public DatasetPermissions permissionsOf(String tenantId, Jwt jwt) {
        return withTenant(tenantId, () -> permissionsResolver.forJwt(jwt));
    }

    /** Dataset permissions from a handshake principal, whose authorities the filter chain already set. */
    public DatasetPermissions permissionsOf(String tenantId, Principal principal) {
        return withTenant(tenantId, () -> permissionsResolver.forPrincipal(principal));
    }

    private <T> T withTenant(String tenantId, java.util.function.Supplier<T> work) {
        String previous = TenantContext.getTenantId();
        try {
            TenantContext.setTenantId(tenantId);
            return work.get();
        } finally {
            restore(previous);
        }
    }

    /**
     * Narrow a requested set of timeseries externalIds down to those whose dataset {@code perms} may
     * read. A read-all caller keeps the whole set without a query; otherwise the readable subset is
     * resolved in SQL (rows in unreadable datasets simply aren't returned), exactly as the REST
     * datapoint read path does. Tenant-scoped: sets {@link TenantContext} for the routing datasource.
     */
    public List<String> readableExternalIds(String tenantId, DatasetPermissions perms,
                                            Collection<String> requested) {
        if (requested == null || requested.isEmpty()) return List.of();
        if (perms.canReadEverything()) return new ArrayList<>(requested);
        Set<Long> allowed = perms.readableIds();
        if (allowed.isEmpty()) return List.of();

        Set<String> requestedSet = new LinkedHashSet<>(requested);
        String previous = TenantContext.getTenantId();
        try {
            TenantContext.setTenantId(tenantId);
            List<TimeseriesEntity> readable = timeseriesRepository
                    .findAllByIdOrExternalIdAndDataSetIdIn(Set.of(), requestedSet, allowed);
            List<String> result = new ArrayList<>();
            for (TimeseriesEntity ts : readable) {
                if (requestedSet.contains(ts.getExternalId())) result.add(ts.getExternalId());
            }
            return result;
        } finally {
            restore(previous);
        }
    }

    /**
     * True if {@code perms} may read <em>every</em> timeseries bound to the subscription. All-or-
     * nothing by necessity: the fan-out topic streams all of a subscription's timeseries with only
     * broker-side keying by subscription id, so a caller who can read some-but-not-all would still
     * receive the datapoints of the ones they cannot read. A read-all caller passes without a query;
     * a subscription with no bound timeseries streams nothing and is trivially permitted.
     */
    public boolean canReadSubscription(String tenantId, DatasetPermissions perms,
                                       String subscriptionExternalId) {
        if (perms.canReadEverything()) return true;
        long hash = ExternalIds.hash(subscriptionExternalId);
        String previous = TenantContext.getTenantId();
        try {
            TenantContext.setTenantId(tenantId);
            List<Long> datasetIds = subscriptionRepository.findTimeseriesDatasetIds(hash);
            return datasetIds.stream().allMatch(perms::canReadDataSet);
        } finally {
            restore(previous);
        }
    }

    private static void restore(String previous) {
        if (previous == null) TenantContext.clear();
        else TenantContext.setTenantId(previous);
    }
}
