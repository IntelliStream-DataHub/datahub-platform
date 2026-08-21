// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.dhconsole.chat.config;

import ai.intellistream.datahub.tenant.Tenant;
import ai.intellistream.datahub.tenant.TenantConfigService;
import ai.intellistream.dhconsole.security.UserSession;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * The single source of truth for whether the AI chat is available to the current request.
 *
 * <p>Access requires all three of:
 * <ul>
 *   <li><b>deployment</b> — {@code datahub.chat.enabled} (the whole feature is compiled/wired in),</li>
 *   <li><b>tenant</b> — the signed-in user's tenant carries the Vault {@code tenant-config.chat} flag,</li>
 *   <li><b>user</b> — the caller holds the {@code DATAHUB_CHAT} authority (a Keycloak role).</li>
 * </ul>
 *
 * <p>Used from two places so the rule can never drift: the layout hides every reference to chat when
 * {@link #available()} is false (the templates call {@code ${@chatAccess.available()}} directly, so
 * it works even on view-controller pages like Analyze that have no {@code @Controller} method), and
 * the chat endpoints refuse with a 403 via {@code @PreAuthorize("@chatAccess.available()")}. Hiding
 * the UI is not the security boundary; the endpoint check is.
 */
@Component("chatAccess")
public class ChatAccess {

    /** Keycloak authority (no ROLE_ prefix, matching DATAHUB_CONSOLE/DATAHUB_ACCESS). */
    static final String CHAT_AUTHORITY = "DATAHUB_CHAT";

    private final boolean deploymentEnabled;
    private final TenantConfigService tenantConfigService;
    private final UserSession userSession;

    public ChatAccess(@Value("${datahub.chat.enabled:false}") boolean deploymentEnabled,
                      TenantConfigService tenantConfigService,
                      UserSession userSession) {
        this.deploymentEnabled = deploymentEnabled;
        this.tenantConfigService = tenantConfigService;
        this.userSession = userSession;
    }

    public boolean available() {
        try {
            return deploymentEnabled && userHasChatAuthority() && tenantHasChat();
        } catch (RuntimeException e) {
            // Belt and braces: on an error/unauthenticated dispatch (no session, no auth) treat chat
            // as unavailable rather than letting the whole page fail.
            return false;
        }
    }

    private boolean userHasChatAuthority() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return false;
        }
        for (GrantedAuthority authority : auth.getAuthorities()) {
            if (CHAT_AUTHORITY.equals(authority.getAuthority())) {
                return true;
            }
        }
        return false;
    }

    private boolean tenantHasChat() {
        String orgId = userSession.getOrganizationId();
        if (orgId == null) {
            return false;
        }
        Tenant tenant = tenantConfigService.getConfig(orgId);
        return tenant != null && tenant.getFeatures().isChatFeatureEnabled();
    }
}
