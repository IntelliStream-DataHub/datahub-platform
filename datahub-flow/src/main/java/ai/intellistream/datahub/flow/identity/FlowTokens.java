// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.flow.identity;

import ai.intellistream.datahub.tenant.Tenant;
import ai.intellistream.datahub.tenant.TenantConfigService;
import ai.intellistream.datahub.tenant.TenantConfigWriter;
import ai.intellistream.datahub.tenant.TenantFlowSection;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.function.Function;
import java.util.function.UnaryOperator;

/**
 * The deployers' offline tokens, kept as {@code flow.token.<flowId>} in the tenant's
 * {@code tenant-config/<org>} secret, never in Postgres. The settings API neither lists nor
 * changes these keys; only this service writes them.
 *
 * <p>Writes go through the secret's compare-and-set, so a token stored at the same moment as an
 * administrator saves the tenant's settings is retried rather than lost. A token that is replaced or
 * removed is also revoked at Keycloak: Vault keeps earlier versions of the secret, so deleting the
 * key alone would leave a working token behind.
 */
@Slf4j
@Component
public class FlowTokens {

    private static final int ATTEMPTS = 3;

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
        UnaryOperator<Map<String, String>> remembering = existing -> {
            previous[0] = TenantFlowSection.token(existing, flowId);
            return change.apply(existing);
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
        if (previous[0] != null && !previous[0].equals(token)) {
            keycloak.revoke(previous[0]);
        }
    }
}
