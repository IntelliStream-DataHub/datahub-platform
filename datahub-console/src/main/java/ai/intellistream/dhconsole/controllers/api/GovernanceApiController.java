// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.dhconsole.controllers.api;

import ai.intellistream.datahub.api.responses.DataWrapper;
import ai.intellistream.datahub.models.GovernanceTemplateDTO;
import ai.intellistream.dhconsole.api.DatahubApi;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/api/governance")
@RequiredArgsConstructor
public class GovernanceApiController {

    private final DatahubApi datahubApi;

    @GetMapping(value = "/templates", produces = "application/json")
    public ResponseEntity<DataWrapper<GovernanceTemplateDTO>> listTemplates() {
        return ResponseEntity.ok(datahubApi.getGovernanceTemplates());
    }

    @GetMapping(value = "/templates/{templateId}", produces = "application/json")
    public ResponseEntity<DataWrapper<GovernanceTemplateDTO>> getTemplateById(@PathVariable Long templateId) {
        DataWrapper<GovernanceTemplateDTO> data = datahubApi.getGovernanceTemplateById(templateId);
        return ResponseEntity.ok(data);
    }
}
