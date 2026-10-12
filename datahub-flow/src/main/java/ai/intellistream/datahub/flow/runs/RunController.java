// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.flow.runs;

import ai.intellistream.datahub.api.responses.DataWrapper;
import ai.intellistream.datahub.flow.runs.RunService.DeployRequest;
import ai.intellistream.datahub.flow.runs.RunService.StartRequest;
import ai.intellistream.datahub.flow.runs.RunView.DeploymentView;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Deployments and runs. A flow is deployed by switching a version on; its runs are created by its
 * schedule or started here, and every instance of the service claims and executes them.
 */
@RestController
public class RunController {

    private final RunService runs;

    public RunController(RunService runs) {
        this.runs = runs;
    }

    /** 200 with the deployment; 404 when the flow is not visible or has never been deployed. */
    @GetMapping("/flows/{externalId}/deployment")
    public DeploymentView deployment(@PathVariable String externalId) {
        return runs.deployment(externalId);
    }

    /**
     * Deploys a version (default: the current one), switched on unless {@code enabled} is false.
     * Its runs act as the caller. 400 when the version no longer compiles.
     */
    @PutMapping("/flows/{externalId}/deployment")
    public DeploymentView deploy(@PathVariable String externalId,
                                 @RequestBody(required = false) DeployRequest request) {
        return runs.deploy(externalId, request);
    }

    /**
     * 201 with a new manual run of the deployed version. 409 when the flow is not deployed or is
     * switched off; 400 when no window is given and the trigger has none.
     */
    @PostMapping("/flows/{externalId}/runs")
    public ResponseEntity<RunView> start(@PathVariable String externalId,
                                         @RequestBody(required = false) StartRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(runs.start(externalId, request));
    }

    /** The runs the caller may see, newest first; of one flow with {@code flow}. */
    @GetMapping("/runs")
    public DataWrapper<RunView> list(@RequestParam(required = false) String flow,
                                     @RequestParam(required = false) String cursor,
                                     @RequestParam(required = false) Integer limit) {
        return runs.list(flow, cursor, limit);
    }

    @GetMapping("/runs/{id}")
    public RunView get(@PathVariable UUID id) {
        return runs.get(id);
    }

    /** 200 with the run; 409 when it has already ended. */
    @PostMapping("/runs/{id}/cancel")
    public RunView cancel(@PathVariable UUID id) {
        return runs.cancel(id);
    }

    /** 201 with a new run over the same window, of the currently deployed version. */
    @PostMapping("/runs/{id}/rerun")
    public ResponseEntity<RunView> rerun(@PathVariable UUID id) {
        return ResponseEntity.status(HttpStatus.CREATED).body(runs.rerun(id));
    }
}
