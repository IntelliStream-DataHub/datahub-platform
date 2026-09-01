// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.controllers;

import ai.intellistream.datahub.api.controllers.errors.BadRequestException;
import ai.intellistream.datahub.api.controllers.errors.BadRequestError;
import ai.intellistream.datahub.api.controllers.errors.ConflictError;
import ai.intellistream.datahub.errors.ObjectNotFoundException;
import ai.intellistream.datahub.api.policy.PolicyScopeValidator;
import ai.intellistream.datahub.api.responses.DataWrapper;
import ai.intellistream.datahub.api.responses.swaggerdto.IdCollectionDataWrapper;
import ai.intellistream.datahub.api.responses.swaggerdto.PolicyDataWrapper;
import ai.intellistream.datahub.models.forms.UpdatePolicyForm;
import ai.intellistream.datahub.api.responses.swaggerdto.UpdatePolicyDataWrapper;
import ai.intellistream.datahub.api.services.PolicyCheckService;
import ai.intellistream.datahub.api.services.PolicyService;
import ai.intellistream.datahub.jpa.domains.PolicyEntity;
import ai.intellistream.datahub.models.*;
import ai.intellistream.datahub.models.policy.NamingCheckForm;
import ai.intellistream.datahub.models.policy.PolicyFinding;
import ai.intellistream.datahub.responses.BuildErrorResponse;
import ai.intellistream.datahub.api.controllers.errors.DuplicateDataException;
import ai.intellistream.datahub.api.controllers.errors.DuplicateError;
import ai.intellistream.datahub.errors.ResponseError;
import org.springframework.http.HttpStatusCode;
import ai.intellistream.datahub.transformers.PolicyTransformer;
import ai.intellistream.datahub.transformers.ResourceTransformer;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.ZonedDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/policies")
@RequiredArgsConstructor
@Tag(
        name = "Policies",
        description = "Endpoints for creating, updating, listing and deleting Policy nodes."
)
public class PolicyController {

    private final PolicyService policyService;
    private final PolicyCheckService policyCheckService;

    // 1. LIST ALL POLICY NODES
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            summary = "List all Policy nodes",
            description = "Returns all Policy nodes currently stored in the system."
    )
    @ApiResponse(
            responseCode = "200",
            description = "List of all Policy nodes.",
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = PolicyDataWrapper.class)
            )
    )
    public ResponseEntity<DataWrapper<Policy>> listPolicies() {
        List<Policy> policies = policyService.listAllPolicies();

        DataWrapper<Policy> wrapper = new DataWrapper<>();
        wrapper.getItems().addAll(policies);

        return ResponseEntity.ok(wrapper);
    }

    // 2. LIST ALL POLICY TYPES (STATIC ENUM DEFINITIONS)
    @GetMapping(value = "/types", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            summary = "List all unique Policy types",
            description = """
                    Returns all available Policy definitions (e.g., IS_WRITE_PROTECTED,
                    MASKING_POLICY, HAS_REQUIREMENT, etc.) based on the PolicyType enum.
                    Used by the UI's 'Policies' section."""
    )
    @ApiResponse(
            responseCode = "200",
            description = "List of Policy types.",
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = PolicyDataWrapper.class)
            )
    )
    public ResponseEntity<DataWrapper<Policy>> listPolicyTypes() {
        DataWrapper<Policy> wrapper = new DataWrapper<>();

        for (var type : PolicyType.values()) {
            Policy p = new Policy();
            p.setName(type.name());
            p.setType(type);

            switch (type) {
                case SECURITY_POLICY ->
                        p.setDescription("Restricts data access or actions for security compliance.");
                case ENCRYPTION_POLICY ->
                        p.setDescription("Defines encryption requirements for stored or transmitted data.");
                case MASKING_POLICY ->
                        p.setDescription("Hides or obfuscates sensitive fields such as names or identifiers.");
                case IS_WRITE_PROTECTED ->
                        p.setDescription("Marks a dataset as read-only; blocks writes except delete.");
                case IS_READ_PROTECTED ->
                        p.setDescription("Restricts read access to authorized users only.");
                case HAS_REQUIREMENT ->
                        p.setDescription("Indicates datasets that must meet specific compliance requirements.");
                case NAMING_CONVENTION ->
                        p.setDescription("Enforces the external-id naming convention at write time. "
                                + "Set tenant-wide and optionally overridden per data set. "
                                + "Applies to resources and data sets; events are exempt.");
            }

            wrapper.getItems().add(p);
        }

        return ResponseEntity.ok(wrapper);
    }

    // 3. CREATE POLICY NODE(S)
    @PostMapping(
            value = "/create",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    @Operation(
            summary = "Create policy",
            description = """
                    Create one or more **policy** records from a policy template. A policy
                    attaches to a dataset (via `POST /datasets/update`) and enforces rules
                    like "this dataset is read-only" or "mask the `salary` field".

                    Each entry needs:
                    - `name` — a unique name for the policy.
                    - `templateId` — which policy type to instantiate. List the available
                      types with `GET /policies/types`.
                    - `externalId` — optional, for integrations that need a stable identifier.
                    """
    )
    @ApiResponse(
            responseCode = "201",
            description = "Returns the created Policy node(s).",
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = PolicyDataWrapper.class)
            )
    )
    public ResponseEntity<?> create(
            @RequestBody
            @Schema(implementation = PolicyDataWrapper.class)
            DataWrapper<Policy> form
    ) throws ConstraintViolationException, Exception {
        try {
            Collection<Policy> items = form.getItems();

            // Scope first, over the whole batch: a policy attached where its type does not allow
            // would look configured and enforce nothing. Also validates a naming policy's regex
            // here, where the error reaches the person who typed it rather than an integration
            // that cannot fix it.
            items.forEach(PolicyScopeValidator::validate);

            // One call for the whole batch, through the shared create pipeline — so a
            // three-policy request is judged, authorized and published as one create, exactly like
            // three resources are.
            DataWrapper<Policy> data = policyService.create(items);

            return new ResponseEntity<>(data, HttpStatus.CREATED);

        } catch (ConstraintViolationException cve) {
            var e = BuildErrorResponse.createConstraintViolationError(cve);
            return new ResponseEntity<>(e, HttpStatus.BAD_REQUEST);
        } catch (BadRequestException e) {
            // A scope or naming-config rejection is the caller's mistake, not a server fault; the
            // broad catch below would otherwise report it as a 500 with no usable detail.
            return new ResponseEntity<>(e.getError(), HttpStatus.BAD_REQUEST);
        } catch (DuplicateDataException e) {
            // The shared pipeline's pre-check, which policy create now goes through: it catches a
            // taken external id before the insert, so this is the 409 the caller gets in practice.
            // The DataIntegrityViolationException below stays as the net for a race that slips
            // past the check and reaches the unique index.
            ResponseError<DuplicateError> dupError = e.getError();
            return new ResponseEntity<>(dupError, HttpStatusCode.valueOf(dupError.getError().getCode()));
        } catch (DataIntegrityViolationException dve) {
            // A duplicate externalId is a conflict, not a server fault. This was unhandled, so
            // creating a policy whose externalId already existed produced a bare 500 — the only
            // node type where a duplicate create did not surface as 4xx.
            var e = BuildErrorResponse.createDataIntegrityViolationError(dve);
            return new ResponseEntity<>(e, HttpStatus.CONFLICT);
        }
        // Let the ACL denial and the concurrency conflict reach their advices; the broad catch
        // below would otherwise mask a 403 and a 409 as 500s.
        catch (org.springframework.security.access.AccessDeniedException | OptimisticLockingFailureException e) {
            throw e;
        } catch (Exception e) {
            // Log the detail, return none: the raw exception message was going out to the caller.
            log.error("Failed to create policies", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    // 5. DELETE POLICY NODE(S)
    @Tag(name = "Policies")
    @Operation(
            summary = "Delete policy nodes",
            description = "Delete one or more policy nodes."
    )
    @ApiResponse(responseCode = "204", description = "The policy nodes were deleted. No response body.",
            content = @Content)
    @ApiResponse(responseCode = "409", description =
            "Concurrency conflict — another request modified or deleted the policy " +
                    "between read and write. Clients should re-fetch the current state and retry.",
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = ConflictError.class)
            ))
    @RequestMapping(
            value = { "/delete" },
            method = { RequestMethod.POST, RequestMethod.DELETE },
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    public ResponseEntity<?> deletePolicies(
            @RequestBody
            @Schema(implementation = IdCollectionDataWrapper.class)
            DataWrapper<IdCollection> form
    ) {
        try {
            policyService.deletePolicies(form);
            return ResponseEntity.noContent().build();

        }
        catch (ConstraintViolationException cve) {
            var e = BuildErrorResponse.createConstraintViolationError(cve);
            return new ResponseEntity<>(e, HttpStatus.BAD_REQUEST);
        }
        // Let the concurrency conflict reach ConcurrencyExceptionHandler as a 409 — the broad
        // Exception catch below would otherwise mask it as a 500.
        catch (OptimisticLockingFailureException olf) {
            throw olf;
        } catch (Exception e) {
            log.error(e.getMessage(), e);
            return ResponseEntity.internalServerError().build();
        }
    }

    // 6. GET SINGLE POLICY NODE
    @GetMapping(value = "/{policyNodeId}", produces = MediaType.APPLICATION_JSON_VALUE)
    @Tag(name = "Policies")
    @Operation(summary = "Get a single Policy node by id")
    @ApiResponse(responseCode = "200", description = "The policy node.",
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = PolicyDataWrapper.class)
            ))
    @ApiResponse(responseCode = "404", description = "No policy node with this id exists.")
    public ResponseEntity<?> getPolicyById(
            @Parameter(description = "Numeric id of the policy node.", example = "5677892")
            @PathVariable Long policyNodeId) {
        // A missing policy throws ObjectNotFoundException → 404 (via ObjectNotFoundExceptionHandler).
        PolicyEntity node = policyService.getPolicyById(policyNodeId);
        Resource resource = ResourceTransformer.from(node);
        Policy policy = PolicyTransformer.toPolicy(resource);

        DataWrapper<Policy> wrapper = new DataWrapper<>();
        wrapper.getItems().add(policy);

        return ResponseEntity.ok(wrapper);
    }

    // 7. GENERIC UPDATE (NAME, EXTERNAL ID, METADATA, ACTIVE POLICIES, ETC.)
    @PostMapping(value = "/update", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            summary = "Update a policy",
            description = """
                    Change fields on existing policies. Send one `items[]` entry per policy,
                    identified by `id` or `externalId`, plus an `update` block naming only the
                    fields to change. Every entry is applied; the response returns the updated
                    policies in the same order.

                    The entry's `externalId` says *which* policy to change; to change the
                    external id itself, set `update.externalId`.

                    Each updatable field is an object, the same rules as `POST /resources/update`:
                    - `"set"` — replace the field with this value.
                    - `"setNull": true` — clear the field (`description` and `source` only).
                      Ignored when `set` is also present: a value you supplied is never discarded.
                    - `"add"` / `"remove"` — for `metadata`, add or remove specific keys.

                    A field you leave out is left alone. That matters most for `deactivated`:
                    omitting it keeps a switched-off policy switched off, where the previous
                    whole-object form silently re-activated it on any unrelated edit.
                    """
    )
    @ApiResponse(responseCode = "400", description = "The request carried no policies, or one failed validation.",
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = BadRequestError.class)
            ))
    @ApiResponse(responseCode = "409", description =
            "Concurrency conflict — another request modified or deleted the policy " +
                    "between read and write. Clients should re-fetch the current state and retry.",
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = ConflictError.class)
            ))
    public ResponseEntity<?> updatePolicy(
            @Schema(implementation = UpdatePolicyDataWrapper.class)
            @RequestBody DataWrapper<UpdatePolicyForm> wrapper
    ) throws ConstraintViolationException, Exception{
        // Every other /update endpoint applies the whole batch; this one used to read only
        // items.iterator().next(), silently discarding the rest, and threw NoSuchElementException
        // on an empty wrapper — which the broad catch below turned into a bodyless 500.
        if (wrapper == null || wrapper.getItems() == null || wrapper.getItems().isEmpty()) {
            return ResponseEntity.badRequest().body(new DataWrapper<>());
        }

        try {
            DataWrapper<Policy> resp = new DataWrapper<>();
            for (UpdatePolicyForm incoming : wrapper.getItems()) {
                // No scope check here: an update cannot move a policy between tenant-wide and
                // per-dataset (neither `type` nor `dataSetId` is updatable), so the only rule that
                // can still be broken is the naming config — validated in the service against the
                // metadata the policy ends up with rather than the fragment sent.
                PolicyEntity updated = policyService.updatePolicyNode(incoming);
                resp.getItems().add(PolicyTransformer.toPolicy(updated));
            }

            return ResponseEntity.ok(resp);

        } catch (ConstraintViolationException cve) {
            // Return the validation detail, not an empty envelope — clients need to know which
            // field failed.
            var err = BuildErrorResponse.createConstraintViolationError(cve);
            return new ResponseEntity<>(err, HttpStatus.BAD_REQUEST);
        } catch (BadRequestException e) {
            // PolicyScopeValidator (wrong scope / malformed naming regex) and a missing policy id
            // both surface here — they are the caller's mistake, so 400 with the error body rather
            // than the broad 500 below.
            return new ResponseEntity<>(e.getError(), HttpStatus.BAD_REQUEST);
        } catch (IllegalArgumentException e) {
            return new ResponseEntity<>(e.getMessage(), HttpStatus.BAD_REQUEST);
        }
        // Let concurrency conflicts (409) and not-found (404) reach their dedicated handlers — the
        // broad Exception catch below would otherwise mask them as a 500.
        catch (OptimisticLockingFailureException | org.springframework.security.access.AccessDeniedException
               | ObjectNotFoundException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to update policies", e);
            DataWrapper<Policy> resp = new DataWrapper<>();
            return new ResponseEntity<>(resp, HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @PostMapping(value = "/naming/check",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            summary = "Check external ids against the naming policy",
            description = """
                    Preflight. Runs the naming policy over candidate external ids and reports what it
                    would do, without writing anything.

                    Two uses: validating as you type, and answering "what would this policy do to my
                    existing ids" before enabling it — which is the difference between a policy
                    people trust and one they switch off.

                    Uses the same evaluator as the write path, so the answer cannot disagree with
                    what a real write would do. Only non-conforming ids are returned; an empty
                    `findings` array means every id is fine.

                    This reports what *would* happen. Violations that were allowed through and
                    recorded are a different thing and are not returned here: they are events, so
                    they are read with `POST /events/filter` on `type = "policy_finding"`.
                    """
    )
    @ApiResponse(responseCode = "200", description = "What the policy would decide for each id.")
    @ApiResponse(responseCode = "403", description = "No read access to the requested data set.",
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)
            ))
    public ResponseEntity<Map<String, List<PolicyFinding>>> check(
            @RequestBody @Valid NamingCheckForm form) {
        return new ResponseEntity<>(Map.of("findings", policyCheckService.check(form)), HttpStatus.OK);
    }
}
