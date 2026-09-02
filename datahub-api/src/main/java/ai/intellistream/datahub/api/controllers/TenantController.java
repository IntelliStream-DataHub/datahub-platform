// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.controllers;

import ai.intellistream.datahub.api.datasecurity.DataSecurity;
import ai.intellistream.datahub.api.datasecurity.SettingsSecurity;
import ai.intellistream.datahub.tenant.CallerPermissions;
import ai.intellistream.datahub.tenant.LlmProvider;
import ai.intellistream.datahub.tenant.TenantConfigService;
import ai.intellistream.datahub.tenant.TenantContext;
import ai.intellistream.datahub.tenant.TenantFeatures;
import ai.intellistream.datahub.tenant.TenantLlm;
import ai.intellistream.datahub.tenant.TenantLlmForm;
import ai.intellistream.datahub.tenant.TenantLlmStore;
import ai.intellistream.datahub.tenant.TenantLlmView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.AllArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/tenant")
@AllArgsConstructor
@Tag(name = "Tenant", description = """
        Endpoints for reading settings that apply to the tenant your API token belongs to,
        and to you within it. Useful when your client needs to know which optional features
        are enabled, and what the signed-in caller may do, before showing UI for them.""")
public class TenantController {

    private final TenantConfigService tenantConfigService;
    private final DataSecurity dataSecurity;
    private final SettingsSecurity settingsSecurity;
    private final TenantLlmStore llmStore;

    @Tag(name = "Tenant")
    @Operation(summary = "Get feature flags for your tenant",
            description = """
                    Return a map of optional features and whether they're enabled for your
                    tenant. Clients should use this to gate feature-specific UI and API calls
                    — disabled features may still have endpoints that return 404 or 403.
                    """)
    @GetMapping("/features")
    public ResponseEntity<TenantFeatures> getFeatures() {
        var tenant = tenantConfigService.getConfig(TenantContext.getTenantId());
        return ResponseEntity.ok(tenant.getFeatures());
    }

    @Tag(name = "Tenant")
    @Operation(summary = "Get your own effective permissions",
            description = """
                    Return what you may do with data in your tenant: whether you can read or
                    write every dataset, whether you can manage datasets themselves, and
                    otherwise which dataset ids you hold grants on (already expanded down the
                    dataset hierarchy, so a grant on a parent lists its descendants too).

                    Note that an empty id list means "everything or nothing" — check the
                    matching readAll/writeAll flag first.

                    This is for gating UI and deciding which operations to offer. It is not the
                    enforcement boundary: every request is authorised server-side regardless of
                    what you did with this answer.""")
    @GetMapping("/permissions")
    public ResponseEntity<CallerPermissions> getPermissions() {
        return ResponseEntity.ok(new CallerPermissions(
                dataSecurity.hasReadAccessToEverything(),
                dataSecurity.hasWriteAccessToEverything(),
                dataSecurity.canManageDataSets(),
                dataSecurity.readableDataSetIds(),
                dataSecurity.writableDataSetIds(),
                settingsSecurity.canReadSettings(),
                settingsSecurity.canWriteSettings()));
    }

    @Tag(name = "Tenant")
    @Operation(summary = "Get your tenant's model configuration",
            description = """
                    Which model your tenant's agents run on, and where to reach it. Requires the
                    /settings/read group in your organization.

                    The API key is never returned. `hasApiKey` says whether one is stored, which is
                    the only thing a settings form needs to know; `configured` is false when your
                    tenant has none of its own and is using the deployment default.""")
    @GetMapping("/llm")
    public ResponseEntity<TenantLlmView> getLlm() {
        settingsSecurity.assertCanReadSettings();
        TenantLlm llm = llmStore.read(TenantContext.getTenantId());
        if (llm == null) {
            return ResponseEntity.ok(new TenantLlmView(null, null, null, null, null, false, false));
        }
        return ResponseEntity.ok(new TenantLlmView(
                llm.getProvider() == null ? null : llm.getProvider().name(),
                llm.getModel(),
                llm.getBaseUrl(),
                llm.getReasoningEffort(),
                llm.getTurnTimeout(),
                llm.getApiKey() != null && !llm.getApiKey().isBlank(),
                true));
    }

    @Tag(name = "Tenant")
    @Operation(summary = "Replace your tenant's model configuration",
            description = """
                    Requires the /settings/write group in your organization.

                    Omit `apiKey` to leave the stored key unchanged — a form cannot show you the
                    current key, so treating an unsent field as "clear it" would delete the
                    credential every time anyone edited the model name. Send an empty string to
                    clear it deliberately.

                    Every other field is replaced by what you send, so send the whole configuration
                    you want rather than a diff.""")
    @PutMapping("/llm")
    public ResponseEntity<TenantLlmView> putLlm(@RequestBody TenantLlmForm form) {
        settingsSecurity.assertCanWriteSettings();
        String tenantId = TenantContext.getTenantId();

        TenantLlm existing = llmStore.read(tenantId);
        TenantLlm updated = new TenantLlm();
        updated.setProvider(LlmProvider.parse(form.provider()));
        updated.setModel(blankToNull(form.model()));
        updated.setBaseUrl(blankToNull(form.baseUrl()));
        updated.setReasoningEffort(form.reasoningEffort());
        updated.setTurnTimeout(blankToNull(form.turnTimeout()));
        // null means "leave it"; "" means "clear it". The two are opposite and one is destructive,
        // so they must not collapse into each other here.
        updated.setApiKey(form.apiKey() == null
                ? (existing == null ? null : existing.getApiKey())
                : blankToNull(form.apiKey()));

        llmStore.write(tenantId, updated);
        // So the caller's next read shows what they just saved rather than the five-minute-old
        // value, which reads as a failed save and invites them to do it again.
        tenantConfigService.refreshLlm(tenantId);
        return getLlm();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

}
