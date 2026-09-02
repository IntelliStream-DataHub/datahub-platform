// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.controllers;

import ai.intellistream.datahub.api.datasecurity.DataSecurity;
import ai.intellistream.datahub.tenant.CallerPermissions;
import ai.intellistream.datahub.tenant.TenantConfigService;
import ai.intellistream.datahub.tenant.TenantContext;
import ai.intellistream.datahub.tenant.TenantFeatures;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.AllArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
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
                dataSecurity.writableDataSetIds()));
    }

}
