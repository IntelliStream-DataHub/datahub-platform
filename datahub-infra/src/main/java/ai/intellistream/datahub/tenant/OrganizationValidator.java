// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.tenant;

import lombok.extern.slf4j.Slf4j;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Resolves the request's tenant from the token's {@code organization} claim and puts it in
 * {@link TenantContext}.
 *
 * <p>A token must name <strong>exactly one</strong> organization. Keycloak emits one entry per
 * organization the caller belongs to, and which one applies is the client's choice, made at the
 * token endpoint with {@code scope=organization:<alias>}. A token carrying several
 * ({@code scope=organization:*}) is ambiguous and is rejected rather than resolved by picking
 * one: this used to take {@code values().iterator().next()}, so a multi-organization user got
 * whichever organization happened to serialise first, and every request could have landed on a
 * different tenant's database.
 *
 * <p>See {@code datahub-api/KEYCLOAK_ORG_GROUPS.md} for the client and mapper configuration,
 * including the two ways this claim comes out empty or malformed.
 *
 * <p>Shared by every service that resolves a tenant from a caller's token (datahub-api and the flow
 * service), so that both read the claim the same way. Lives in infra with the security classes
 * {@code compileOnly}: the services that use it have a resource server on their classpath already,
 * and the ones that do not are not given one.
 */
@Slf4j
public class OrganizationValidator implements OAuth2TokenValidator<Jwt> {

    /** Guards {@link #warnIfGroupsAreInTheToken}: one warning per JVM, not one per request. */
    private static final AtomicBoolean GROUPS_IN_TOKEN_WARNED = new AtomicBoolean();

    @Override
    public OAuth2TokenValidatorResult validate(Jwt jwt) {
        Object claim = jwt.getClaim("organization");

        if (claim == null) {
            return failure("Missing required organization context: the token carries no "
                    + "'organization' claim. The client must request the organization scope.");
        }

        // The stock membership mapper emits a flat array of aliases when addOrganizationId is
        // off. There is no id in that shape to resolve a tenant from, and it is a configuration
        // mistake rather than a malformed token, so say which knob to turn.
        if (claim instanceof Collection<?>) {
            return failure("Malformed organization claim: expected an object keyed by alias but "
                    + "got a list. Enable 'addOrganizationId' on the organization membership "
                    + "protocol mapper.");
        }

        if (!(claim instanceof Map<?, ?> orgs) || orgs.isEmpty()) {
            return failure("Missing required organization context: the 'organization' claim is empty.");
        }

        if (orgs.size() > 1) {
            return failure("Ambiguous organization context: the token names " + orgs.size()
                    + " organizations " + orgs.keySet()
                    + ". Request a single one with scope=organization:<alias>.");
        }

        Object only = orgs.values().iterator().next();
        if (only instanceof Map<?, ?> details && details.get("id") != null) {
            warnIfGroupsAreInTheToken(details);
            TenantContext.setTenantId(details.get("id").toString());
            return OAuth2TokenValidatorResult.success();
        }
        return failure("Malformed organization claim: no 'id' for organization "
                + orgs.keySet() + ". Enable 'addOrganizationId' on the organization membership "
                + "protocol mapper.");
    }

    private static OAuth2TokenValidatorResult failure(String description) {
        log.warn("Rejecting token: {}", description);
        return OAuth2TokenValidatorResult.failure(
                new OAuth2Error("invalid_token", description, null));
    }

    /**
     * A configuration smell, not a bad token. The dataset grants are read from the UserInfo
     * endpoint whatever the token says, so a token carrying them is still valid and still
     * resolves the same tenant; it is not rejected.
     *
     * <p>What it does mean is that the realm's organization group-membership mapper has
     * drifted from {@code access.token.claim=false} (see
     * {@code deploy/keycloak/bootstrap-org-groups.sh}), which puts every grant back on every
     * request and re-couples revocation to token expiry. Nothing else notices, because nothing
     * else reads them, so this is the only place the drift is visible.
     *
     * <p>Logged once per JVM. This runs on every request, and a per-request warning about a
     * misconfiguration nobody can fix from here would be a log flood rather than a signal.
     */
    private static void warnIfGroupsAreInTheToken(Map<?, ?> organizationDetails) {
        if (organizationDetails.get("groups") == null
                || !GROUPS_IN_TOKEN_WARNED.compareAndSet(false, true)) {
            return;
        }
        log.warn("The access token carries organization groups. DataHub ignores them and reads "
                + "the dataset grants from UserInfo, so access is unaffected, but the "
                + "organization group-membership mapper should have access.token.claim=false "
                + "and id.token.claim=false. See datahub-api/KEYCLOAK_ORG_GROUPS.md.");
    }
}
