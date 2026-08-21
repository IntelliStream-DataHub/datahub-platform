// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.datasecurity;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static ai.intellistream.datahub.api.datasecurity.TestDataSecurity.authorities;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The chain from Keycloak identity to a resolved permission set: organization groups → dataset
 * external ids and wildcard flags → ids expanded through the hierarchy, with the
 * {@code DATAHUB_ADMIN} realm role short-circuiting it entirely.
 */
class DatasetPermissionsResolverTest {

    private final OrgGroupResolver orgGroupResolver = mock(OrgGroupResolver.class);
    private final DatasetClosureService closureService = mock(DatasetClosureService.class);
    private final DatasetPermissionsResolver resolver =
            new DatasetPermissionsResolver(orgGroupResolver, closureService);

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
        DatasetPermissionsResolver.clearCurrent();
    }

    private static Jwt jwt(String... realmRoles) {
        return Jwt.withTokenValue("raw")
                .header("alg", "none")
                .subject("user-1")
                .claim("realm_access", Map.of("roles", List.of(realmRoles)))
                .issuedAt(Instant.ofEpochSecond(1))
                .expiresAt(Instant.ofEpochSecond(9_999_999))
                .build();
    }

    private void authenticate(Jwt jwt, String... authorities) {
        SecurityContextHolder.getContext().setAuthentication(
                new JwtAuthenticationToken(jwt, TestDataSecurity.authorities(authorities)));
    }

    private void groupsAre(String... paths) {
        when(orgGroupResolver.groupsForCurrentCaller()).thenReturn(List.of(paths));
        when(orgGroupResolver.groupsFor(any())).thenReturn(List.of(paths));
    }

    private void closureIs(Set<String> externalIds, Set<Long> datasetIds) {
        when(closureService.closureOfExternalIds(argThat(c -> c != null && Set.copyOf(c).equals(externalIds))))
                .thenReturn(datasetIds);
    }

    // ---- the chain ---------------------------------------------------------------------------

    @Test
    void expandsGroupPathsIntoDatasetIds() {
        authenticate(jwt("DATAHUB_ACCESS"), "ROLE_DATAHUB_ACCESS");
        groupsAre("/datasets/data_set_sap/read", "/datasets/data_set_sap/write");
        closureIs(Set.of("data_set_sap"), Set.of(10L, 11L));

        DatasetPermissions perms = resolver.forCurrentRequest();

        // The grant on data_set_sap covers its descendants too.
        assertThat(perms.canRead(10)).isTrue();
        assertThat(perms.canRead(11)).isTrue();
        assertThat(perms.canRead(99)).isFalse();
        assertThat(perms.canWrite(11)).isTrue();
    }

    /** Read and write expand independently, so a read-only group grants no write. */
    @Test
    void readAndWriteExpandSeparately() {
        authenticate(jwt("DATAHUB_ACCESS"), "ROLE_DATAHUB_ACCESS");
        groupsAre("/datasets/data_set_a/read", "/datasets/data_set_b/write");
        closureIs(Set.of("data_set_a"), Set.of(1L));
        closureIs(Set.of("data_set_b"), Set.of(2L));

        DatasetPermissions perms = resolver.forCurrentRequest();

        assertThat(perms.canRead(1)).isTrue();
        assertThat(perms.canRead(2)).isFalse();
        assertThat(perms.canWrite(2)).isTrue();
        assertThat(perms.canWrite(1)).isFalse();
    }

    @Test
    void noGroupsGrantNothing() {
        authenticate(jwt("DATAHUB_ACCESS"), "ROLE_DATAHUB_ACCESS");
        groupsAre();
        when(closureService.closureOfExternalIds(anyCollection())).thenReturn(Set.of());

        DatasetPermissions perms = resolver.forCurrentRequest();

        assertThat(perms.canRead(1)).isFalse();
        assertThat(perms.canReadEverything()).isFalse();
    }

    @Test
    void noAuthenticationGrantsNothing() {
        SecurityContextHolder.clearContext();

        assertThat(resolver.forCurrentRequest().canRead(1)).isFalse();
        verifyNoInteractions(orgGroupResolver, closureService);
    }

    // ---- blanket short-circuits ---------------------------------------------------------------

    /** An admin's answer cannot depend on any group, so neither Keycloak nor Postgres is touched. */
    @Test
    void adminSkipsGroupResolutionEntirely() {
        authenticate(jwt("DATAHUB_ADMIN"), "ROLE_DATAHUB_ACCESS", "ROLE_DATAHUB_ADMIN");

        DatasetPermissions perms = resolver.forCurrentRequest();

        assertThat(perms.canReadEverything()).isTrue();
        assertThat(perms.canWriteEverything()).isTrue();
        verifyNoInteractions(orgGroupResolver, closureService);
    }

    /**
     * The wildcard groups grant everything without any closure expansion — but unlike admin, they
     * <em>are</em> groups, so the group lookup itself still runs.
     */
    @Test
    void wildcardGroupsGrantEverythingWithoutExpansion() {
        authenticate(jwt("DATAHUB_ACCESS"), "ROLE_DATAHUB_ACCESS");
        groupsAre("/datasets/*/read", "/datasets/*/write");

        DatasetPermissions perms = resolver.forCurrentRequest();

        assertThat(perms.canReadEverything()).isTrue();
        assertThat(perms.canWriteEverything()).isTrue();
        verify(orgGroupResolver, times(1)).groupsForCurrentCaller();
        verifyNoInteractions(closureService);
    }

    /**
     * A wildcard-read caller still needs their write groups expanded, but expanding the read side
     * would be wasted work since canRead short-circuits on the flag.
     */
    @Test
    void wildcardReadStillExpandsWriteGrantsOnly() {
        authenticate(jwt("DATAHUB_ACCESS"), "ROLE_DATAHUB_ACCESS");
        groupsAre("/datasets/*/read", "/datasets/data_set_b/write");
        closureIs(Set.of("data_set_b"), Set.of(2L));

        DatasetPermissions perms = resolver.forCurrentRequest();

        assertThat(perms.canReadEverything()).isTrue();
        assertThat(perms.canRead(999)).isTrue();
        assertThat(perms.canWrite(2)).isTrue();
        assertThat(perms.canWrite(3)).isFalse();
        // Only the write side was expanded.
        verify(closureService, times(1)).closureOfExternalIds(anyCollection());
    }

    /**
     * The retired blanket realm roles must grant nothing: a stale {@code DATAHUB_DATASET_ALL} still
     * assigned in someone's realm is just an unrecognised role.
     */
    @Test
    void retiredBlanketRolesGrantNothing() {
        authenticate(jwt("DATAHUB_DATASET_ALL"),
                "ROLE_DATAHUB_ACCESS", "ROLE_DATAHUB_DATASET_ALL", "ROLE_DATAHUB_DATASET_READ_ALL");
        groupsAre();
        when(closureService.closureOfExternalIds(anyCollection())).thenReturn(Set.of());

        DatasetPermissions perms = resolver.forCurrentRequest();

        assertThat(perms.canReadEverything()).isFalse();
        assertThat(perms.canWriteEverything()).isFalse();
        assertThat(perms.canRead(1)).isFalse();
    }

    // ---- per-request memoisation ---------------------------------------------------------------

    /**
     * DataSecurity calls this from ~25 places per request and each resolution can reach Valkey, so
     * the answer must be computed once.
     */
    @Test
    void resolvesOncePerRequest() {
        authenticate(jwt("DATAHUB_ACCESS"), "ROLE_DATAHUB_ACCESS");
        groupsAre("/datasets/data_set_sap/read");
        closureIs(Set.of("data_set_sap"), Set.of(10L));

        for (int i = 0; i < 5; i++) {
            assertThat(resolver.forCurrentRequest().canRead(10)).isTrue();
        }

        verify(orgGroupResolver, times(1)).groupsForCurrentCaller();
    }

    /**
     * The memo must not survive the request. Tomcat reuses worker threads, so a leaked permission
     * set would hand the next caller on this thread another caller's grants.
     */
    @Test
    void memoIsDroppedOnClear() {
        authenticate(jwt("DATAHUB_ACCESS"), "ROLE_DATAHUB_ACCESS");
        groupsAre("/datasets/data_set_sap/read");
        closureIs(Set.of("data_set_sap"), Set.of(10L));
        resolver.forCurrentRequest();

        DatasetPermissionsResolver.clearCurrent();

        // A different caller lands on the same thread and must not inherit the first one's grants.
        groupsAre("/datasets/data_set_other/read");
        closureIs(Set.of("data_set_other"), Set.of(20L));
        DatasetPermissions second = resolver.forCurrentRequest();

        assertThat(second.canRead(20)).isTrue();
        assertThat(second.canRead(10)).isFalse();
        verify(orgGroupResolver, times(2)).groupsForCurrentCaller();
    }

    // ---- off the request thread ----------------------------------------------------------------

    @Test
    void resolvesFromAnExplicitJwtWithoutMemoising() {
        Jwt token = jwt("DATAHUB_ACCESS");
        groupsAre("/datasets/data_set_sap/read");
        closureIs(Set.of("data_set_sap"), Set.of(10L));

        assertThat(resolver.forJwt(token).canRead(10)).isTrue();
        assertThat(resolver.forJwt(token).canRead(10)).isTrue();

        // A WebSocket connection outlives any request, so pinning the answer to the thread would
        // leak it to whatever ran there next.
        verify(orgGroupResolver, times(2)).groupsFor(token);
    }

    @Test
    void adminShortCircuitsFromAnExplicitJwtToo() {
        DatasetPermissions perms = resolver.forJwt(jwt("DATAHUB_ADMIN"));
        assertThat(perms.canReadEverything()).isTrue();
        assertThat(perms.canWriteEverything()).isTrue();
        verifyNoInteractions(orgGroupResolver, closureService);
    }

    @Test
    void nullJwtAndNonJwtPrincipalGrantNothing() {
        assertThat(resolver.forJwt(null).canRead(1)).isFalse();
        assertThat(resolver.forPrincipal(new UsernamePasswordAuthenticationToken("u", "p", List.of()))
                .canRead(1)).isFalse();
        verifyNoInteractions(orgGroupResolver, closureService);
    }

    @Test
    void resolvesFromAHandshakePrincipal() {
        Jwt token = jwt("DATAHUB_ACCESS");
        var principal = new JwtAuthenticationToken(token, authorities("ROLE_DATAHUB_ACCESS"));
        groupsAre("/datasets/data_set_sap/write");
        closureIs(Set.of("data_set_sap"), Set.of(10L));

        assertThat(resolver.forPrincipal(principal).canWrite(10)).isTrue();
    }
}
