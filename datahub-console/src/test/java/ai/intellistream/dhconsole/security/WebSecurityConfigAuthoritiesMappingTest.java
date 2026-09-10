// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.dhconsole.security;

import ai.intellistream.dhconsole.security.AuthoritiesMappingProperties.IssuerAuthoritiesMappingProperties;
import ai.intellistream.dhconsole.security.AuthoritiesMappingProperties.IssuerAuthoritiesMappingProperties.ClaimMappingProperties;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The claim-to-authority mapping in {@link WebSecurityConfig.GrantedAuthoritiesMapperImpl}, which
 * decides what every logged-in user is allowed to do.
 *
 * <p>The json-path expressions come from Vault ({@code oauth.realm-roles-jsonpath} and
 * {@code oauth.client-roles-jsonpath}, see {@code ConsoleVaultSecrets}) and are evaluated by
 * {@code com.jayway.jsonpath}. That made the library a silent single point of failure: nothing
 * covered this path, so a bump that changed how {@code $.resource_access.*.roles} resolves would
 * have cost every user their client roles with a green build. These tests pin the claim shapes
 * Keycloak actually sends.
 */
class WebSecurityConfigAuthoritiesMappingTest {

    /** A Keycloak id-token payload: realm roles, plus client roles under two clients. */
    private static Map<String, Object> keycloakClaims() {
        return Map.of(
                "realm_access", Map.of("roles", List.of("DATAHUB_ACCESS", "offline_access")),
                "resource_access", Map.of(
                        "datahub-console", Map.of("roles", List.of("CONSOLE_USER")),
                        "account", Map.of("roles", List.of("view-profile", "manage-account"))));
    }

    private static IssuerAuthoritiesMappingProperties mappingFor(String... jsonPaths) {
        IssuerAuthoritiesMappingProperties issuer = new IssuerAuthoritiesMappingProperties();
        issuer.claims = Stream.of(jsonPaths).map(path -> {
            ClaimMappingProperties claim = new ClaimMappingProperties();
            claim.jsonPath = path;
            return claim;
        }).toArray(ClaimMappingProperties[]::new);
        return issuer;
    }

    private static List<String> authorities(Map<String, Object> claims, String... jsonPaths) {
        return WebSecurityConfig.GrantedAuthoritiesMapperImpl
                .extractAuthorities(claims, mappingFor(jsonPaths))
                .stream().map(GrantedAuthority::getAuthority).toList();
    }

    @Test
    void realmRolesBecomeAuthorities() {
        assertThat(authorities(keycloakClaims(), "$.realm_access.roles"))
                .containsExactlyInAnyOrder("DATAHUB_ACCESS", "offline_access");
    }

    /**
     * The wildcard is the fragile one: it matches every client under {@code resource_access}, so
     * json-path returns a list of lists that the mapper has to flatten.
     */
    @Test
    void clientRolesAcrossEveryClientAreFlattened() {
        assertThat(authorities(keycloakClaims(), "$.resource_access.*.roles"))
                .containsExactlyInAnyOrder("CONSOLE_USER", "view-profile", "manage-account");
    }

    @Test
    void realmAndClientRolesAreCombined() {
        assertThat(authorities(keycloakClaims(),
                "$.realm_access.roles", "$.resource_access.*.roles"))
                .containsExactlyInAnyOrder("DATAHUB_ACCESS", "offline_access",
                        "CONSOLE_USER", "view-profile", "manage-account");
    }

    /** A token without the claim must not fail the login; the path simply contributes nothing. */
    @Test
    void anAbsentClaimYieldsNoAuthoritiesRatherThanThrowing() {
        assertThat(authorities(Map.of("sub", "user-1"), "$.realm_access.roles")).isEmpty();
    }

    @Test
    void anEmptyRoleListYieldsNoAuthorities() {
        assertThat(authorities(Map.of("realm_access", Map.of("roles", List.of())),
                "$.realm_access.roles")).isEmpty();
    }

    /** A provider that hands back a scalar string is split on commas, not taken whole. */
    @Test
    void aCommaSeparatedStringClaimIsSplit() {
        assertThat(authorities(Map.of("roles", "DATAHUB_ACCESS,CONSOLE_USER"), "$.roles"))
                .containsExactlyInAnyOrder("DATAHUB_ACCESS", "CONSOLE_USER");
    }
}
