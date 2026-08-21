// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.dhconsole.controllers.api;

import ai.intellistream.datahub.api.responses.DataWrapper;
import ai.intellistream.datahub.models.Policy;
import ai.intellistream.datahub.models.forms.UpdatePolicyForm;
import ai.intellistream.datahub.models.IdCollection;
import ai.intellistream.dhconsole.api.DatahubApi;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/api/policies")
@RequiredArgsConstructor
public class PolicyApiController {

    private final DatahubApi datahubApi;

    /**
     * List all policy nodes.
     */
    @GetMapping
    public ResponseEntity<DataWrapper<Policy>> listPolicies() {
        return ResponseEntity.ok(datahubApi.getPolicies());
    }

    /**
     * List available policy types (IS_WRITE_PROTECTED, MASKING_POLICY...)
     */
    @GetMapping("/types")
    public ResponseEntity<DataWrapper<Policy>> listPolicyTypes() {
        return ResponseEntity.ok(datahubApi.getPolicyTypes());
    }

    /**
     * Load a specific policy node.
     */
    @GetMapping("/{policyNodeId}")
    public ResponseEntity<DataWrapper<Policy>> getPolicyById(@PathVariable Long policyNodeId) {
        return ResponseEntity.ok(datahubApi.getPolicyById(policyNodeId));
    }

    /**
     * Create policy node(s).
     */
    @PostMapping("/create")
    public ResponseEntity<DataWrapper<Policy>> createPolicies(@RequestBody DataWrapper<Policy> wrapper) {
        return ResponseEntity.ok(datahubApi.createPolicies(wrapper));
    }

    /**
     * Update a policy node (name, externalId, metadata etc.)
     * Backend now automatically applies templates if templateId changes.
     */
    @PostMapping("/update")
    public ResponseEntity<DataWrapper<Policy>> updatePolicy(@RequestBody DataWrapper<UpdatePolicyForm> wrapper) {
        return ResponseEntity.ok(datahubApi.updatePolicy(wrapper));
    }

    /**
     * Delete one or more policy nodes (matches dataset delete API)
     */
    @DeleteMapping("/delete")
    public ResponseEntity<Void> deletePolicies(@RequestBody DataWrapper<IdCollection> wrapper) {
        datahubApi.deletePolicies(wrapper);
        return ResponseEntity.noContent().build();
    }

}
