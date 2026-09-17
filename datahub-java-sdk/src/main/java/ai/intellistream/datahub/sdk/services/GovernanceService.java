// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.sdk.services;

import ai.intellistream.datahub.api.responses.DataWrapper;
import ai.intellistream.datahub.models.GovernanceTemplateDTO;
import ai.intellistream.datahub.sdk.http.ApiHttp;
import tools.jackson.databind.JavaType;

/**
 * Governance templates — the compliance rules (retention, access restrictions, required metadata)
 * a dataset can be held to. Read-only over the api: a template is attached to a dataset through
 * that dataset's policy, and DataHub then enforces it on reads and writes.
 */
public final class GovernanceService {

    private final ApiHttp http;
    private final JavaType templates; // DataWrapper<GovernanceTemplateDTO>

    public GovernanceService(ApiHttp http) {
        this.http = http;
        this.templates = http.typeFactory()
                .constructParametricType(DataWrapper.class, GovernanceTemplateDTO.class);
    }

    /** GET /governance/templates — every governance template available to the tenant. */
    public DataWrapper<GovernanceTemplateDTO> listTemplates() {
        return http.get("/governance/templates", templates);
    }

    /** GET /governance/templates/{templateId} — one template by its numeric id. */
    public DataWrapper<GovernanceTemplateDTO> getTemplateById(long templateId) {
        return http.get("/governance/templates/" + templateId, templates);
    }
}
