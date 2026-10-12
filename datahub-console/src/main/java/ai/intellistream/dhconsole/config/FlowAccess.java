// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.dhconsole.config;

import ai.intellistream.datahub.tenant.Tenant;
import ai.intellistream.datahub.tenant.TenantConfigService;
import ai.intellistream.dhconsole.security.UserSession;
import org.springframework.stereotype.Component;

/**
 * Whether the signed-in user's tenant has flows: its Vault {@code tenant-config.flow} flag.
 *
 * <p>Decides only what the layout shows (the Flows entry and its Needs-attention count, reached as
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
        try {
            String orgId = userSession.getOrganizationId();
            if (orgId == null) {
                return false;
            }
            Tenant tenant = tenantConfigService.getConfig(orgId);
            return tenant != null && tenant.getFeatures().isFlowFeatureEnabled();
        } catch (RuntimeException e) {
            // An error or unauthenticated dispatch has no session: show no Flows entry rather than
            // fail the page.
            return false;
        }
    }
}
