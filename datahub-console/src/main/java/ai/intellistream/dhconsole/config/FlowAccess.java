// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.dhconsole.config;

import ai.intellistream.datahub.tenant.Tenant;
import ai.intellistream.datahub.tenant.TenantConfigService;
import ai.intellistream.dhconsole.security.UserSession;
import org.springframework.stereotype.Component;

import java.util.function.Predicate;

/**
 * Whether the signed-in user's tenant has flows: its Vault {@code tenant-config.flow} flag.
 *
 * <p>Decides only what the layout shows (the Flows entry and its Failed-runs count, reached as
 * {@code ${@flowAccess.available()}}). It is not the boundary: datahub-flow refuses every call for a
 * tenant without the flag, and who may see or change a flow is that service's question, answered
 * per flow from the caller's groups and dataset grants.
 */
@Component("flowAccess")
public class FlowAccess {

    private final TenantConfigService tenantConfigService;
    private final UserSession userSession;

    public FlowAccess(TenantConfigService tenantConfigService, UserSession userSession) {
        this.tenantConfigService = tenantConfigService;
        this.userSession = userSession;
    }

    public boolean available() {
        return check(tenant -> tenant.getFeatures().isFlowFeatureEnabled());
    }

    /**
     * Flows, and a model to run an agent on: the tenant's own {@code llm.*} settings, usable. No
     * role beyond flow access: an agent is a flow, and the only thing a role would gate is the
     * tenant spending its own model budget, which it already controls through those settings.
     */
    public boolean agentsAvailable() {
        return check(tenant -> tenant.getFeatures().isFlowFeatureEnabled()
                && tenant.getLlm() != null && tenant.getLlm().isUsable());
    }

    private boolean check(Predicate<Tenant> test) {
        try {
            String orgId = userSession.getOrganizationId();
            if (orgId == null) {
                return false;
            }
            Tenant tenant = tenantConfigService.getConfig(orgId);
            return tenant != null && test.test(tenant);
        } catch (RuntimeException e) {
            // An error or unauthenticated dispatch has no session: show no entry rather than fail
            // the page.
            return false;
        }
    }
}
