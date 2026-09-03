// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.dhconsole.chat.config;

import ai.intellistream.datahub.tenant.Tenant;
import ai.intellistream.datahub.tenant.TenantConfigService;
import ai.intellistream.datahub.tenant.TenantLlm;
import ai.intellistream.dhconsole.security.UserSession;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Resolves the model configuration for the signed-in user's tenant.
 *
 * <p>Two layers, narrower first: the tenant's own entry — which model, and how to reach it — then
 * the deployment-wide defaults for anything it leaves unset, plus the budgets, which stay
 * deployment-wide. Nothing here fails for a missing layer — a tenant with no entry uses
 * the deployment default, which is what every tenant did before this existed, and a half-built
 * session on an error dispatch does the same rather than throwing.
 */
@Component
@ConditionalOnProperty(prefix = "datahub.chat", name = "enabled", havingValue = "true")
public class ChatSettingsResolver {

    private final ChatProperties properties;
    private final TenantConfigService tenantConfigService;
    private final UserSession userSession;

    public ChatSettingsResolver(ChatProperties properties, TenantConfigService tenantConfigService,
                                UserSession userSession) {
        this.properties = properties;
        this.tenantConfigService = tenantConfigService;
        this.userSession = userSession;
    }

    public ChatSettings forCurrentTenant() {
        return forTenant(tenantLlm());
    }

    /** Visible for tests, and the seam a caller with its own tenant would reuse. */
    ChatSettings forTenant(TenantLlm llm) {
        return new ChatSettings(
                pick(llm == null ? null : llm.getProvider(), properties.getProvider()),
                pickText(llm == null ? null : llm.getApiKey(), properties.getApiKey()),
                pickText(llm == null ? null : llm.getModel(), properties.getModel()),
                pickText(llm == null ? null : llm.getBaseUrl(), properties.getBaseUrl()),
                pickText(llm == null ? null : llm.getReasoningEffort(), properties.getReasoningEffort()),
                pick(llm == null ? null : llm.getTurnTimeoutDuration(), properties.getTurnTimeout()),
                // Budgets stay deployment-wide: a tenant picks its model, not your spend per call.
                properties.getMaxOutputTokens());
    }

    private TenantLlm tenantLlm() {
        String orgId = userSession.getOrganizationId();
        if (orgId == null) {
            return null;
        }
        Tenant tenant = tenantConfigService.getConfig(orgId);
        return tenant == null ? null : tenant.getLlm();
    }

    private static <T> T pick(T narrower, T fallback) {
        return narrower != null ? narrower : fallback;
    }

    /** As {@link #pick}, but a blank string counts as unset — Vault fields are hand-written. */
    private static String pickText(String narrower, String fallback) {
        return narrower == null || narrower.isBlank() ? fallback : narrower.strip();
    }

    /** Overload so the generic {@link #pick} is not ambiguous at the call site. */
    private static Duration pick(Duration narrower, Duration fallback) {
        return narrower != null ? narrower : fallback;
    }
}
