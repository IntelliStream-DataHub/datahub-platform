// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.controllers;

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
        Endpoints for reading settings that apply to the tenant your API token belongs to.
        Useful when your client needs to know which optional features are enabled before
        showing UI for them.""")
public class TenantController {

    private final TenantConfigService tenantConfigService;

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

}
