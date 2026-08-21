// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.config;

import ai.intellistream.datahub.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tenant resolution from the token's {@code organization} claim.
 *
 * <p>The claim shapes below are pasted from a live Keycloak 26.7, including the two malformed ones
 * that a misconfigured realm actually produces. See {@code datahub-api/KEYCLOAK_ORG_GROUPS.md}.
 */
class OrganizationValidatorTest {

    private final SecurityConfig.OrganizationValidator validator = new SecurityConfig.OrganizationValidator();

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private static Jwt tokenWith(Object organizationClaim) {
        Jwt.Builder builder = Jwt.withTokenValue("t")
                .header("alg", "none")
                .subject("user-1")
                .issuedAt(Instant.ofEpochSecond(1))
                .expiresAt(Instant.ofEpochSecond(9_999_999));
        if (organizationClaim != null) {
            builder.claim("organization", organizationClaim);
        } else {
            builder.claim("sub", "user-1");
        }
        return builder.build();
    }

    private static Map<String, Object> org(String id) {
        return Map.of("id", id);
    }

    private static String errorOf(OAuth2TokenValidatorResult result) {
        return result.getErrors().iterator().next().getDescription();
    }

    /** The shape a correctly configured realm emits for scope=organization:acme. */
    @Test
    void resolvesTheTenantFromASingleOrganization() {
        OAuth2TokenValidatorResult result = validator.validate(
                tokenWith(Map.of("acme", org("11111111-1111-1111-1111-111111111111"))));

        assertThat(result.hasErrors()).isFalse();
        assertThat(TenantContext.getTenantId()).isEqualTo("11111111-1111-1111-1111-111111111111");
    }

    /**
     * A realm whose group-membership mapper has drifted and also writes the grants into the access
     * token. They are read from UserInfo regardless, so the token stays valid and resolves the same
     * tenant; the drift is only logged (once per JVM).
     */
    @Test
    void acceptsATokenThatAlsoCarriesGroups() {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("id", "11111111-1111-1111-1111-111111111111");
        details.put("groups", List.of("/datasets/data_set_sap/read"));

        OAuth2TokenValidatorResult result = validator.validate(tokenWith(Map.of("acme", details)));

        assertThat(result.hasErrors()).isFalse();
        assertThat(TenantContext.getTenantId()).isEqualTo("11111111-1111-1111-1111-111111111111");
    }

    /**
     * The bug this replaced: a multi-organization token used to resolve to whichever organization
     * serialised first, so the same user could land on a different tenant's database per request.
     * Ambiguity is now rejected, and the message says how to fix it.
     */
    @Test
    void rejectsAnAmbiguousMultiOrganizationToken() {
        Map<String, Object> orgs = new LinkedHashMap<>();
        orgs.put("acme", org("org-a"));
        orgs.put("beta", org("org-b"));

        OAuth2TokenValidatorResult result = validator.validate(tokenWith(orgs));

        assertThat(result.hasErrors()).isTrue();
        assertThat(errorOf(result))
                .contains("Ambiguous organization context")
                .contains("scope=organization:<alias>");
        // Critically, no tenant was set: the request must not proceed against a guessed database.
        assertThat(TenantContext.getTenantId()).isNull();
    }

    @Test
    void rejectsATokenWithNoOrganizationClaim() {
        OAuth2TokenValidatorResult result = validator.validate(tokenWith(null));

        assertThat(result.hasErrors()).isTrue();
        assertThat(errorOf(result)).contains("no 'organization' claim");
        assertThat(TenantContext.getTenantId()).isNull();
    }

    @Test
    void rejectsAnEmptyOrganizationClaim() {
        OAuth2TokenValidatorResult result = validator.validate(tokenWith(Map.of()));

        assertThat(result.hasErrors()).isTrue();
        assertThat(errorOf(result)).contains("empty");
    }

    /**
     * What the stock membership mapper emits with addOrganizationId off: a flat array of aliases,
     * carrying no id. A configuration mistake rather than a bad token, so the message names the
     * setting instead of saying "missing organization context".
     */
    @Test
    void rejectsTheFlatAliasArrayAndNamesTheSetting() {
        OAuth2TokenValidatorResult result = validator.validate(tokenWith(List.of("acme")));

        assertThat(result.hasErrors()).isTrue();
        assertThat(errorOf(result))
                .contains("Malformed organization claim")
                .contains("addOrganizationId");
    }

    @Test
    void rejectsAnOrganizationWithNoId() {
        OAuth2TokenValidatorResult result = validator.validate(
                tokenWith(Map.of("acme", Map.of("name", "Acme"))));

        assertThat(result.hasErrors()).isTrue();
        assertThat(errorOf(result)).contains("addOrganizationId");
        assertThat(TenantContext.getTenantId()).isNull();
    }

    @Test
    void rejectsANullId() {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("id", null);

        OAuth2TokenValidatorResult result = validator.validate(
                tokenWith(Map.of("acme", details)));

        assertThat(result.hasErrors()).isTrue();
        assertThat(TenantContext.getTenantId()).isNull();
    }
}
