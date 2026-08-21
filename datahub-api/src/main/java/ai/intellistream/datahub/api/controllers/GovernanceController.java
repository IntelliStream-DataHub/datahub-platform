// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.controllers;

import ai.intellistream.datahub.api.responses.DataWrapper;
import ai.intellistream.datahub.api.responses.swaggerdto.GovernanceTemplateDataWrapper;
import ai.intellistream.datahub.api.services.GovernanceService;
import ai.intellistream.datahub.models.GovernanceTemplateDTO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/governance")
@RequiredArgsConstructor
@Tag(
        name = "Governance",
        description = """
                Governance templates describe the compliance rules (retention, access
                restrictions, required metadata fields) a dataset can be held to. Attach a
                template to a dataset via its policy, then DataHub enforces the rules on
                reads and writes automatically.""")
public class GovernanceController {

    private final GovernanceService governanceService;

    @GetMapping(value = "/templates", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            summary = "List all governance templates",
            description = "Return every governance template available to your tenant."
    )
    @ApiResponse(
            responseCode = "200",
            description = "List of governance templates",
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = GovernanceTemplateDataWrapper.class)
            )
    )
    public ResponseEntity<DataWrapper<GovernanceTemplateDTO>> listTemplates() {
        return ResponseEntity.ok(governanceService.listTemplates());
    }

    @GetMapping(value = "/templates/{templateId}", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            summary = "Get a governance template by id",
            description = "Look up one governance template by its numeric `templateId`."
    )
    @ApiResponse(
            responseCode = "200",
            description = "Governance template",
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = GovernanceTemplateDataWrapper.class)
            )
    )
    public ResponseEntity<DataWrapper<GovernanceTemplateDTO>> getTemplateById(
            @Parameter(description = "Numeric id of the governance template.", example = "12")
            @PathVariable Long templateId) {
        return ResponseEntity.ok(governanceService.getTemplateById(templateId));
    }
}

