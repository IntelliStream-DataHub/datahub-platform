// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.controllers;

import ai.intellistream.datahub.api.datasecurity.SettingsSecurity;
import ai.intellistream.datahub.api.services.TenantSettingsService;
import ai.intellistream.datahub.models.tenant.SettingsPermission;
import ai.intellistream.datahub.models.tenant.SettingsScopes;
import ai.intellistream.datahub.models.tenant.TenantLlmSettings;
import ai.intellistream.datahub.models.tenant.TenantLlmSettingsForm;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * The settings a tenant administers for itself, as opposed to the entitlements an operator grants
 * it. Today that is which model its AI assistant runs on.
 *
 * <p>Lives in datahub-api rather than the console because the console is a frontend: it renders the
 * form and the browser calls this directly. That also keeps the Vault write credential in one
 * service instead of two.
 */
@RestController
@RequestMapping("/tenant/settings")
@Tag(name = "Tenant settings", description = """
        Settings your organization administers for itself, granted per scope: the
        /settings/<scope>/read and /settings/<scope>/write groups in your Keycloak
        organization, or /settings/*/read and /settings/*/write for every scope. Read and
        write are separate grants and write does not imply read.""")
public class TenantSettingsController {

    private final TenantSettingsService settingsService;
    private final SettingsSecurity settingsSecurity;

    public TenantSettingsController(TenantSettingsService settingsService,
                                    SettingsSecurity settingsSecurity) {
        this.settingsService = settingsService;
        this.settingsSecurity = settingsSecurity;
    }

    @Operation(summary = "What this caller may do with settings",
            description = """
                    What you may read and change, per settings scope. Wildcard grants are already
                    resolved, so every scope this platform knows is listed by name. Intended for
                    gating UI: a client should use this to decide between showing a form, showing
                    it read-only, or not showing it at all. It is not the security boundary — the
                    endpoints below enforce the same grants regardless.
                    """)
    @GetMapping("/permissions")
    public ResponseEntity<Map<String, SettingsPermission>> permissions() {
        // Deliberately ungated: answering "you may do nothing" is not a disclosure, and a client
        // that must call a 403-ing endpoint to discover it may not call it has learned nothing.
        return ResponseEntity.ok(settingsSecurity.grants().byScope());
    }

    @Operation(summary = "Get your organization's model configuration",
            description = """
                    The model your AI assistant runs on. The API key is never returned; `apiKeySet`
                    says whether one is stored. `configured` is whether this amounts to a model that
                    can actually be called — when it is false your organization has no assistant.
                    """)
    @GetMapping("/llm")
    public ResponseEntity<TenantLlmSettings> getLlm() {
        settingsSecurity.assertCanRead(SettingsScopes.LLM);
        return ResponseEntity.ok(settingsService.readLlm());
    }

    @Operation(summary = "Change your organization's model configuration",
            description = """
                    Replaces the model configuration and returns it as stored. Omit `apiKey` to keep
                    the stored credential, send it empty to remove it, or send a value to replace
                    it — so a form showing a masked key can be saved without retyping it.

                    Takes effect for this API immediately. Other services cache the tenant registry
                    and pick the change up within five minutes.
                    """)
    @PutMapping("/llm")
    public ResponseEntity<TenantLlmSettings> updateLlm(@RequestBody TenantLlmSettingsForm form) {
        settingsSecurity.assertCanWrite(SettingsScopes.LLM);
        return ResponseEntity.ok(settingsService.updateLlm(form));
    }
}
