// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.dhconsole.chat.config;

import ai.intellistream.datahub.llm.LlmSettings;
import ai.intellistream.datahub.tenant.Tenant;
import ai.intellistream.datahub.tenant.TenantConfigService;
import ai.intellistream.datahub.tenant.TenantLlm;
import ai.intellistream.dhconsole.security.UserSession;
import org.springframework.stereotype.Component;

/**
 * Resolves the model configuration for the signed-in user's tenant.
 *
 * <p>The model, the credential and the endpoint come from the tenant and nowhere else. There is no
 * deployment-wide model to inherit, so this cannot quietly hand a tenant someone else's credential;
 * an unconfigured tenant has no assistant, and {@link ChatAccess} has already said so before
 * anything here runs.
 *
 * <p>Everything else — effort, the token roof, the iteration cap, the timeout, the instructions —
 * is the tenant's if it said something and the deployment's default ({@link ChatProperties}) if it
 * did not. These are not ceilings the deployment imposes: a tenant on its own credential pays its
 * own bill and may spend it however it likes.
 */
@Component
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
    public LlmSettings forCurrentTenant() {
        TenantLlm llm = tenantLlm();
        if (llm == null || !llm.isUsable()) {
            throw new IllegalStateException(
                    "This tenant has no model configured. Set llm.provider, llm.model and either "
                            + "llm.api-key or llm.base-url on its tenant-config/<org-name> secret.");
        }
        return LlmSettings.of(llm, properties.defaults());
    }

    private TenantLlm tenantLlm() {
        String orgId = userSession.getOrganizationId();
        if (orgId == null) {
            return null;
        }
        Tenant tenant = tenantConfigService.getConfig(orgId);
        return tenant == null ? null : tenant.getLlm();
    }
}
