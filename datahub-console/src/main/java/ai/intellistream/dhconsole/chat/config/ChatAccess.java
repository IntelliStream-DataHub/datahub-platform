// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.dhconsole.chat.config;

import ai.intellistream.datahub.tenant.Tenant;
import ai.intellistream.datahub.tenant.TenantConfigService;
import ai.intellistream.dhconsole.security.UserSession;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * The single source of truth for whether the AI chat is available to the current request.
 *
 * <p>Access requires both of:
 * <ul>
 *   <li><b>tenant</b> — the signed-in user's tenant carries the Vault {@code tenant-config.chat}
 *       flag <em>and</em> has configured a model of its own,</li>
 *   <li><b>user</b> — the caller holds the {@code DATAHUB_CHAT} authority (a Keycloak role).</li>
 * </ul>
 *
 * <p>There is no deployment switch. There was one, back when the deployment supplied the model and
 * the credential; now it supplies neither, so a deployment that configures nothing has no tenant
 * with a model and therefore no chat, without anyone having to remember a property. Both flags
 * default to off, so nothing turns itself on.
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

    private final TenantConfigService tenantConfigService;
    private final UserSession userSession;

    public ChatAccess(TenantConfigService tenantConfigService, UserSession userSession) {
        this.tenantConfigService = tenantConfigService;
        this.userSession = userSession;
    }

    public boolean available() {
        try {
            return userHasChatAuthority() && tenantHasChat();
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

    /**
     * The tenant has switched chat on <em>and</em> configured a model of its own.
     *
     * <p>Both, because they answer different questions: the feature flag is whether this tenant is
     * meant to have an assistant, and the model is whether one can be built. There is no house
     * credential to fall back on, so a tenant that has configured nothing sees no panel rather than
     * one that fails on its first message — and never runs on the deployment's own key.
     */
    private boolean tenantHasChat() {
        String orgId = userSession.getOrganizationId();
        if (orgId == null) {
            return false;
        }
        Tenant tenant = tenantConfigService.getConfig(orgId);
        return tenant != null
                && tenant.getFeatures().isChatFeatureEnabled()
                && tenant.getLlm() != null
                && tenant.getLlm().isUsable();
    }
}
