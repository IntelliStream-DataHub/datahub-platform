// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.flow.flows;

import ai.intellistream.datahub.api.responses.DataWrapper;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Flows and their versions. A flow is saved as its JSON definition, exactly as written: the body
 * of a create or a change is the definition itself, read with the definition format's own rules
 * (an unknown field is an error, not ignored).
 *
 * <p>Access is decided per flow by its dataset (see {@link FlowService}). A flow whose dataset was
 * deleted is switched off and shown, in lists and directly, to whoever may write every dataset:
 * they can move it by saving it into another dataset, or delete it.
 */
@RestController
@RequestMapping("/flows")
public class FlowController {

    private final FlowService flows;

    public FlowController(FlowService flows) {
        this.flows = flows;
    }

    /** 201 with the flow; 400 invalid, 403 not allowed, 409 the external id is taken. */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<FlowView> create(@RequestBody String definition) {
        return ResponseEntity.status(HttpStatus.CREATED).body(flows.create(definition));
    }

    /** 200 with the flow's validity and problems; never saves. 403 without {@code /flows/write}. */
    @PostMapping(path = "/validate", consumes = MediaType.APPLICATION_JSON_VALUE)
    public FlowView.Validation validate(@RequestBody String definition) {
        return flows.validate(definition);
    }

    /** The flows the caller may see, by external id, in pages; of one {@code kind} ({@code flow}, {@code agent}) when given. */
    @GetMapping
    public DataWrapper<FlowView> list(@RequestParam(required = false) String kind,
                                      @RequestParam(required = false) String cursor,
                                      @RequestParam(required = false) Integer limit) {
        return flows.list(kind, cursor, limit);
    }

    /** 200 with the flow and its current definition; 404 when it does not exist for the caller. */
    @GetMapping("/{externalId}")
    public FlowView get(@PathVariable String externalId) {
        return flows.get(externalId);
    }

    /** Saves the definition as the flow's next version. 409 when someone else saved in between. */
    @PutMapping(path = "/{externalId}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public FlowView update(@PathVariable String externalId, @RequestBody String definition) {
        return flows.update(externalId, definition);
    }

    /**
     * Deletes the flow with its versions and runs: 204. 403 without {@code /flows/write} or write
     * access to its dataset (all datasets, for a flow whose dataset was deleted); 404 when it does
     * not exist for the caller.
     */
    @DeleteMapping("/{externalId}")
    public ResponseEntity<Void> delete(@PathVariable String externalId) {
        flows.delete(externalId);
        return ResponseEntity.noContent().build();
    }

    /** Every version, newest first, without definitions. */
    @GetMapping("/{externalId}/versions")
    public List<FlowView.Version> versions(@PathVariable String externalId) {
        return flows.versions(externalId);
    }

    @GetMapping("/{externalId}/versions/{version}")
    public FlowView.Version version(@PathVariable String externalId, @PathVariable int version) {
        return flows.version(externalId, version);
    }
}
