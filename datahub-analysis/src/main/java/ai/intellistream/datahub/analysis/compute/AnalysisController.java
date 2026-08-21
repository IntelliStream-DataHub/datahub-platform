// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.analysis.compute;

import ai.intellistream.datahub.models.analysis.AnalysisResponse;
import ai.intellistream.datahub.models.forms.AnalysisForm;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * The analysis service's browser-facing endpoint. Guarded by
 * {@link ai.intellistream.datahub.analysis.config.SecurityConfig} (a valid user JWT with
 * {@code ROLE_DATAHUB_ACCESS}); the console posts an {@link AnalysisForm} directly here. The
 * {@link AnalysisService} then gathers the ACL'd series + graph from the api (forwarding the caller's
 * JWT) and runs the relationship analysis in-process.
 */
@RestController
public class AnalysisController {

    private final AnalysisService analysisService;

    public AnalysisController(AnalysisService analysisService) {
        this.analysisService = analysisService;
    }

    @PostMapping(path = "/analysis",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public AnalysisResponse analyze(@RequestBody AnalysisForm form) {
        return analysisService.analyze(form);
    }
}
