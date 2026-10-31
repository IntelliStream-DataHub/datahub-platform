// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.flow.identity;

import ai.intellistream.datahub.tenant.Tenant;
import ai.intellistream.datahub.tenant.TenantConfigService;
import ai.intellistream.datahub.tenant.TenantConfigWriter;
import ai.intellistream.datahub.tenant.TenantFlowSection;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.UnaryOperator;

/**
 * The deployers' offline tokens, kept as {@code flow.token.<flowId>} in the tenant's
 * {@code tenant-config/<org>} secret, never in Postgres. The settings API neither lists nor
 * changes these keys; only this service writes them.
 *
 * <p>Writes go through the secret's compare-and-set, so a token stored at the same moment as an
 * administrator saves the tenant's settings is retried rather than lost. A token that is replaced or
 * removed is also revoked at Keycloak, since Vault keeps earlier versions of the secret and deleting
 * the key alone would leave a working token behind — but only when no token left in the secret is
 * the same person's. Revoking an offline token ends at least the offline session behind it, and a
 * refresh, a redeploy from the same sign-in and the person's other flows hold tokens of that
 * session: revoking a replaced token would end the very token that replaced it.
 */
@Slf4j
@Component
public class FlowTokens {

    private static final int ATTEMPTS = 3;
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final Function<String, String> orgName;
    private final TenantConfigWriter writer;
    private final KeycloakTokens keycloak;

    @Autowired
    public FlowTokens(TenantConfigService tenants, TenantConfigWriter writer, KeycloakTokens keycloak) {
        this(tenantId -> {
            Tenant tenant = tenants.getConfig(tenantId);
            if (tenant == null) {
                throw new IllegalStateException("No tenant " + tenantId);
            }
            return tenant.getOrganizationName();
        }, writer, keycloak);
    }

    FlowTokens(Function<String, String> orgName, TenantConfigWriter writer, KeycloakTokens keycloak) {
        this.orgName = orgName;
        this.writer = writer;
        this.keycloak = keycloak;
    }

    /** The offline token a flow's runs act with, or null when it has none. Read from Vault each time. */
    public String get(String tenantId, long flowId) {
        return TenantFlowSection.token(writer.current(orgName.apply(tenantId)), flowId);
    }

    /** Stores a flow's offline token, and revokes the one it replaces. */
    public void store(String tenantId, long flowId, String token) {
        replace(tenantId, flowId, TenantFlowSection.putToken(flowId, token), token);
    }

    /** Removes a flow's offline token, as its runs stop acting as a person, and revokes it. */
    public void remove(String tenantId, long flowId) {
        replace(tenantId, flowId, TenantFlowSection.removeToken(flowId), null);
    }

    private void replace(String tenantId, long flowId, UnaryOperator<Map<String, String>> change,
                         String token) {
        String org = orgName.apply(tenantId);
        String[] previous = new String[1];
        AtomicReference<Map<String, String>> after = new AtomicReference<>();
        UnaryOperator<Map<String, String>> remembering = existing -> {
            previous[0] = TenantFlowSection.token(existing, flowId);
            after.set(change.apply(existing));
            return after.get();
        };
        for (int attempt = 1; ; attempt++) {
            try {
                writer.update(org, remembering);
                break;
            } catch (IllegalStateException e) {
                // Most likely someone else changed the secret between our read and write.
                if (attempt == ATTEMPTS) {
                    throw e;
                }
                log.info("Storing flow {}'s token for tenant {} conflicted; trying again", flowId, tenantId);
            }
        }
        String gone = previous[0];
        if (gone == null || gone.equals(token)) {
            return;
        }
        // A token whose person cannot be read is kept too: it may share a session with one still stored.
        String person = subject(gone);
        if (person == null || TenantFlowSection.tokens(after.get()).stream().anyMatch(t -> person.equals(subject(t)))) {
            log.info("Flow {}'s replaced token for tenant {} is not revoked: a token still stored may "
                    + "belong to the same offline session", flowId, tenantId);
            return;
        }
        keycloak.revoke(gone);
    }

    /** Whose a Keycloak offline token is ({@code sub}), read without verifying it; null if unreadable. */
    private static String subject(String token) {
        String[] parts = token.split("\\.");
        if (parts.length != 3) {
            return null;
        }
        try {
            JsonNode claims = JSON.readTree(new String(Base64.getUrlDecoder().decode(parts[1]),
                    StandardCharsets.UTF_8));
            JsonNode sub = claims.get("sub");
            return sub == null || !sub.isString() ? null : sub.asString();
        } catch (RuntimeException e) {
            return null;
        }
    }
}
