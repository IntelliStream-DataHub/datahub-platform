// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.datasecurity;

import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Turns a caller's identity into a resolved {@link DatasetPermissions}.
 *
 * <p>The chain is: Keycloak organization groups ({@link OrgGroupResolver}) → dataset external ids
 * and wildcard flags ({@link DatasetGrants}) → dataset ids expanded through the {@code BELONGS_TO}
 * hierarchy ({@link DatasetClosureService}) → permissions. The wildcard grants
 * ({@code /datasets/*&#47;read|write}) set the blanket flags without any expansion. Only the
 * {@code DATAHUB_ADMIN} realm role short-circuits the group lookup entirely.
 *
 * <h2>Resolved once per request</h2>
 * {@link DataSecurity} calls this from ~25 places across a single request. Each resolution can
 * reach Valkey, so the answer is memoised in a {@link ThreadLocal} for the duration of the request
 * and cleared by {@code RequestStateCleanupFilter}. Same pattern, and the same hazard,
 * as {@code TenantContext}: Tomcat reuses worker threads, so a permission set left behind would be
 * read by the next request on that thread. That would be a cross-user authorization leak, so the
 * clear is not optional.
 *
 * <p>Within one request the caller's grants cannot change, so memoising costs nothing in accuracy.
 */
@Service
@Slf4j
public class DatasetPermissionsResolver {

    private static final ThreadLocal<DatasetPermissions> CURRENT = new ThreadLocal<>();

    private final OrgGroupResolver orgGroupResolver;
    private final DatasetClosureService closureService;

    public DatasetPermissionsResolver(OrgGroupResolver orgGroupResolver,
                                      DatasetClosureService closureService) {
        this.orgGroupResolver = orgGroupResolver;
        this.closureService = closureService;
    }

    /**
     * The authenticated caller's permissions, resolved once and reused for the rest of the request.
     * Returns {@link DatasetPermissions#none()} when there is no JWT principal.
     */
    public DatasetPermissions forCurrentRequest() {
        DatasetPermissions memoised = CURRENT.get();
        if (memoised != null) {
            return memoised;
        }
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null) {
            return DatasetPermissions.none();
        }
        DatasetPermissions resolved =
                resolve(authentication.getAuthorities(), orgGroupResolver::groupsForCurrentCaller);
        CURRENT.set(resolved);
        return resolved;
    }

    /**
     * Permissions for an explicit decoded token, for callers off the request thread. The WebSocket
     * handlers authenticate a token by hand and have no {@code SecurityContext}, so they resolve
     * once at connection time and hold the result.
     *
     * <p>Not memoised: a WebSocket connection outlives any request, and pinning a permission set to
     * a container thread would leak it to whatever ran there next.
     */
    public DatasetPermissions forJwt(Jwt jwt) {
        if (jwt == null) {
            return DatasetPermissions.none();
        }
        return resolve(authoritiesOf(jwt), () -> orgGroupResolver.groupsFor(jwt));
    }

    /** Permissions for a handshake principal, whose authorities the filter chain already set. */
    public DatasetPermissions forPrincipal(java.security.Principal principal) {
        if (principal instanceof JwtAuthenticationToken jwtAuth) {
            return resolve(jwtAuth.getAuthorities(), () -> orgGroupResolver.groupsFor(jwtAuth.getToken()));
        }
        return DatasetPermissions.none();
    }

    /**
     * The group lookup is a {@link Supplier} rather than a value so it is never invoked for a
     * caller whose token already decides the answer. It reaches UserInfo and Valkey, and an admin
     * request should not pay for it — nor depend on either being reachable.
     */
    private DatasetPermissions resolve(Collection<? extends GrantedAuthority> authorities,
                                       Supplier<List<String>> groupPaths) {
        if (DatasetPermissions.isAdmin(authorities)) {
            // The operator escape hatch: answered from the token alone, so admin access keeps
            // working when the identity plumbing behind the group lookup is degraded.
            return DatasetPermissions.allDatasets();
        }

        DatasetGrants grants = DatasetGrants.from(groupPaths.get());
        // Expanding alongside a wildcard flag would be wasted work: canRead/canWrite short-circuit
        // on the flag regardless of what the id set holds.
        Set<Long> readable = grants.readAll()
                ? Set.of() : closureService.closureOfExternalIds(grants.readExternalIds());
        Set<Long> writable = grants.writeAll()
                ? Set.of() : closureService.closureOfExternalIds(grants.writeExternalIds());

        return DatasetPermissions.of(grants.readAll(), grants.writeAll(), readable, writable);
    }

    /**
     * Reproduce the {@code SecurityConfig} mapping of realm roles to {@code ROLE_}-prefixed
     * authorities, for a token that no filter chain has processed.
     */
    public static List<GrantedAuthority> authoritiesOf(Jwt jwt) {
        return StreamAccessAuthorizer.realmRoles(jwt).stream()
                .map(role -> (GrantedAuthority) new SimpleGrantedAuthority("ROLE_" + role))
                .toList();
    }

    /**
     * Drop the memoised permissions for this thread. Called from the servlet filter chain; must run
     * even when the request failed, or the next request on this pooled thread inherits them.
     */
    public static void clearCurrent() {
        CURRENT.remove();
    }
}
