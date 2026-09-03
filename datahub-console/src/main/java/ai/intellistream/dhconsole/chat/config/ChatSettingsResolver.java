// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.dhconsole.chat.config;

import ai.intellistream.datahub.tenant.Tenant;
import ai.intellistream.datahub.tenant.TenantConfigService;
import ai.intellistream.datahub.tenant.TenantLlm;
import ai.intellistream.dhconsole.security.UserSession;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Resolves the model configuration for the signed-in user's tenant.
 *
 * <p>The model, the credential and the endpoint come from the tenant and nowhere else. There is no
 * deployment-wide model to inherit, so this cannot quietly hand a tenant someone else's credential;
 * an unconfigured tenant has no assistant, and {@link ChatAccess} has already said so before
 * anything here runs. What the deployment still supplies is the spend and patience every tenant
 * runs inside.
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

    /**
     * @throws IllegalStateException if the tenant has no usable model. Reachable only if the secret
     *         is emptied between {@link ChatAccess#available()} and the turn, which is why it is an
     *         exception and not a return value the callers would have to keep re-checking.
     */
    public ChatSettings forCurrentTenant() {
        TenantLlm llm = tenantLlm();
        if (llm == null || !llm.isUsable()) {
            throw new IllegalStateException(
                    "This tenant has no model configured. Set llm.provider, llm.model and either "
                            + "llm.api-key or llm.base-url on its tenant-config/<org-name> secret.");
        }
        return forTenant(llm);
    }

    /** Visible for tests, and the seam a caller with its own tenant would reuse. */
    ChatSettings forTenant(TenantLlm llm) {
        return new ChatSettings(
                llm.getProvider(),
                strip(llm.getApiKey()),
                strip(llm.getModel()),
                strip(llm.getBaseUrl()),
                strip(llm.getReasoningEffort()),
                llm.getTurnTimeoutDuration() != null
                        ? llm.getTurnTimeoutDuration() : properties.getTurnTimeout(),
                // A tenant picks its model, not your spend per call.
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

    /** Vault fields are hand-written, so a stray space is not a different credential. */
    private static String strip(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
