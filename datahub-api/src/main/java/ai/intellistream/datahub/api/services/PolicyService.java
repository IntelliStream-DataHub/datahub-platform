// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.services;

import ai.intellistream.datahub.api.datasecurity.DataSecurity;
import ai.intellistream.datahub.api.policy.NamingPolicyResolver;
import ai.intellistream.datahub.api.responses.DataWrapper;
import ai.intellistream.datahub.api.responses.GraphDataWrapper;
import ai.intellistream.datahub.api.controllers.errors.BadRequestError;
import ai.intellistream.datahub.api.controllers.errors.BadRequestException;
import ai.intellistream.datahub.api.policy.PolicyScopeValidator;
import ai.intellistream.datahub.errors.ResponseError;
import ai.intellistream.datahub.models.forms.PolicyFields;
import ai.intellistream.datahub.models.forms.UpdatePolicyForm;
import ai.intellistream.datahub.api.services.node.NodeUpdateService;
import ai.intellistream.datahub.models.UpdateResourceForm;
import ai.intellistream.datahub.models.validation.ResourceFields;
import ai.intellistream.datahub.api.policy.PolicyEnforcement;
import ai.intellistream.datahub.models.policy.PolicyFinding;
import ai.intellistream.datahub.errors.ObjectNotFoundException;
import ai.intellistream.datahub.helpers.utils.IdGenerator;
import ai.intellistream.datahub.jpa.domains.*;
import ai.intellistream.datahub.jpa.dto.PolicyResponseDTO;
import ai.intellistream.datahub.models.EdgeProxy;
import ai.intellistream.datahub.models.IdCollection;
import ai.intellistream.datahub.models.Policy;
import ai.intellistream.datahub.models.Resource;
import ai.intellistream.datahub.repositories.governance.GovernanceTemplateRepository;
import ai.intellistream.datahub.repositories.node.NodeRepository;
import ai.intellistream.datahub.transformers.PolicyTransformer;
import ai.intellistream.datahub.transformers.ResourceTransformer;
import ai.intellistream.datahub.models.RelForm;
import ai.intellistream.datahub.pulsar.EventAction;
import ai.intellistream.datahub.pulsar.EventObject;
import ai.intellistream.datahub.tenant.TenantContext;
import org.springframework.context.ApplicationEventPublisher;
import ai.intellistream.datahub.api.messaging.outbox.GraphOutbox;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZonedDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class PolicyService {

    private final NodeRepository nodeRepository;
    private final ResourceService resourceService;
    private final GovernanceTemplateRepository governanceTemplateRepo;
    private final ai.intellistream.datahub.repositories.node.PolicyRepository policyRepository;
    private final ApplicationEventPublisher applicationEventPublisher;
    private final GraphOutbox graphOutbox;

    /**
     * Policies are what make a dataset write-protected, so writing one is the same class of act as
     * writing the dataset itself: it changes what existing grants let people do. Every policy
     * mutation therefore requires the all-datasets write grant, exactly as dataset create/update/
     * delete does — see {@link DataSecurity#canManageDataSets()}.
     *
     * <p>Reads are deliberately <em>not</em> narrowed, for the same reason dataset reads are not: a
     * policy is attached to the unit access is granted on rather than living inside one, so there is
     * no dataset membership to narrow by. Narrowing would have to mean "policies on datasets you
     * hold a grant on", which is a different feature.
     */
    private final DataSecurity dataSecurity;

    /**
     * Bumped on every policy write. The write path resolves the tenant's naming policy from a cache
     * keyed on a generation counter, so an edited policy that does not invalidate it stays inert
     * until the TTL expires — a rule someone changed and that did not take effect.
     */
    private final NamingPolicyResolver namingPolicyResolver;

    /** The one node-update pipeline; see {@link NodeUpdateService}. */
    private final NodeUpdateService nodeUpdateService;

    /** Records naming-policy warnings, the same way the resource and timeseries paths do. */
    private final PolicyEnforcement policyEnforcement;

    /**
     * Create policy nodes through the shared resource pipeline.
     *
     * <p>Was a hand-rolled copy of {@code ResourceService.create}, run once per item from the
     * controller — its own save, its own edge, its own CUD event each. That made policy creates the
     * one create in the node family no naming policy ever judged, the one that skipped the
     * create-side external-id and data-set checks, and the one where a three-policy request emitted
     * three events instead of one. It now builds {@link Policy} bodies and hands the batch over,
     * the same way the data set and function adapters do. The manage gate is not repeated here: the
     * pipeline applies it to any body carrying the POLICY type-label, whichever endpoint the
     * request came through.
     *
     * <p>{@code dataSetId} is deliberately <em>not</em> put on a body. A policy node is an orphan by
     * construction (POLICY_DATASETID_BUG.md) and the pipeline rejects a POLICY body naming a data
     * set outright; the field has always meant "attach it with an {@code ENFORCED_ON} edge", so
     * that is what it becomes. Each edge names its new node by external id, which resolves because
     * the pipeline flushes its nodes before it maps relations.
     */
    @Transactional
    public DataWrapper<Policy> create(Collection<Policy> items)
            throws org.apache.pulsar.client.api.PulsarClientException {

        var graph = new GraphDataWrapper<ai.intellistream.datahub.models.NodeModel, RelForm>();
        // External id per item, in request order, so the deactivated pass below can find each
        // created node again without relying on the echo's ordering.
        List<String> externalIds = new ArrayList<>();

        for (Policy item : items) {
            Policy body = new Policy();

            // `name` is the display name; the graph identifies a policy by the "POLICY" label
            // (graph-network.js keys off labels.includes("POLICY") to render it and open the policy
            // editor), so the label must be POLICY — not the name. The Policy DTO seeds that label
            // itself, and the pipeline creates the label row and the node_labels link from it.
            String name = item.getName();
            body.setName((name != null && !name.isBlank()) ? name : "Policy");

            // User-defined, or a random UUIDv7 fallback. Resolved here rather than left to the
            // pipeline because the ENFORCED_ON edge below has to name the node by it.
            String externalId = (item.getExternalId() != null && !item.getExternalId().isBlank())
                    ? item.getExternalId()
                    : IdGenerator.getRandomUUID7AsString();
            body.setExternalId(externalId);
            externalIds.add(externalId);

            if (item.getDescription() != null && !item.getDescription().isBlank()) {
                body.setDescription(item.getDescription());
            }
            if (item.getTemplateId() != null) {
                body.getMetadata().put("templateId", String.valueOf(item.getTemplateId()));
            }
            // The form-supplied policy config (kind + params), verbatim. Inert for now — the graph
            // just carries it (createResource flattens each key to a metadata_<key> node prop).
            if (item.getMetadata() != null && !item.getMetadata().isEmpty()) {
                body.getMetadata().putAll(item.getMetadata());
            }
            graph.getNodes().add(body);

            if (item.getDataSetId() != null) {
                RelForm relForm = new RelForm();
                relForm.setFromId(item.getDataSetId());
                relForm.setToExternalId(externalId);
                relForm.setRelationshipType("ENFORCED_ON");
                relForm.setDataSetId(item.getDataSetId());
                graph.getRelations().add(relForm);
            }
        }

        var created = resourceService.create(graph);

        // Re-read as entities. The pipeline has already published one CUD event for the batch and
        // invalidated the naming resolver.
        Map<Long, PolicyEntity> byExternalIdHash = new LinkedHashMap<>();
        List<Long> hashes = externalIds.stream().map(ai.intellistream.datahub.helpers.text.ExternalIds::hash).toList();
        policyRepository.findAllByExternalIdHashIn(new ArrayList<>(hashes))
                .forEach(e -> byExternalIdHash.put(e.getExternalIdHash(), e));

        var result = new DataWrapper<Policy>();
        int index = 0;
        for (Policy item : items) {
            PolicyEntity node = byExternalIdHash.get(hashes.get(index++));
            if (node == null) {
                throw new ObjectNotFoundException("Policy was created but could not be read back.");
            }
            // Create honours the flag too, so a policy can be restored from an export already
            // switched off rather than only by creating it live and disabling it afterwards.
            if (item.isDeactivated()) {
                node = setDeactivated(node.getId(), true);
            }
            result.getItems().add(PolicyTransformer.toPolicy(node));
        }
        // The pipeline judged these names; re-wrapping the response would otherwise swallow what it
        // found, the same way the data set and function adapters carry their warnings out.
        result.setWarnings(created.getWarnings());
        return result;
    }

    /**
     * Switch a policy on or off without touching anything else about it, so activating one restores
     * exactly the rule it was.
     */
    @Transactional
    public PolicyEntity setDeactivated(Long policyId, boolean deactivated) {
        dataSecurity.assertCanManageDataSets();
        PolicyEntity node = policyRepository.findById(policyId)
                .orElseThrow(() -> new RuntimeException("Policy node not found: " + policyId));
        node.setDeactivated(deactivated);
        PolicyEntity saved = policyRepository.save(node);
        // Same reasoning as updateTemplate: switching a policy off changes what is enforced, so the
        // graph must see it. This path published nothing either.
        publishPolicyUpsert(saved);
        namingPolicyResolver.invalidate();
        return saved;
    }

    // 2. GENERIC UPDATE: NAME, EXTERNALID, METADATA (INCL. ACTIVEPOLICIES)
    @Transactional
    public PolicyEntity updatePolicyNode(UpdatePolicyForm form) {

        // Ahead of the lookup, deliberately, and again inside the pipeline. The pipeline's gate is
        // the one that guarantees this for every path into a policy; this one guarantees the
        // caller learns nothing by asking. Resolving first would answer "no such policy" (404) for
        // an id that does not exist and "forbidden" (403) for one that does, which is an
        // enumeration oracle for someone who may not touch policies at all.
        dataSecurity.assertCanManageDataSets();

        boolean hasExternalId = form.getExternalId() != null && !form.getExternalId().isBlank();
        if (form.getId() == null && !hasExternalId) {
            throw new IllegalArgumentException("Policy id or externalId is required for update");
        }

        // Resolved here, not by the pipeline: this lookup is scoped to policies, so an id naming
        // some other kind of node is "no such policy" (404) rather than the pipeline's generic
        // "resource cannot be found" (400). Identify by either id or externalId, like every other
        // update endpoint — a null id simply never matches, so the OR resolves to whichever the
        // caller supplied.
        PolicyEntity node = policyRepository
                .findByIdOrExternalId(form.getId(), hasExternalId ? form.getExternalId() : null)
                .orElseThrow(() -> new ObjectNotFoundException("Policy node not found: "
                        + (form.getId() != null ? form.getId() : form.getExternalId())));

        // An item naming a policy but carrying no changes is a no-op, not a null dereference.
        PolicyFields fields = form.getUpdate() != null ? form.getUpdate() : new PolicyFields();

        // Judged on the input, before anything is applied, so a rejected update costs no writes.
        if (fields.getName().getSet() != null) {
            requireNonBlank(fields.getName().getSet(), "name");
        }
        if (fields.getExternalId().getSet() != null) {
            requireNonBlank(fields.getExternalId().getSet(), "externalId");
        }

        // 1. THE SHARED HALF, through the one pipeline. name / externalId / description / source /
        // metadata are ordinary node fields, so the engine applies them — and with them come the
        // ACL (the manage grant, since this is a PolicyEntity), the type-label guard, and the
        // naming policy. That last one is new: a policy rename used to be the one rename in the
        // system no naming convention was allowed to judge.
        // The caller's form carries the id too: authorize() stamps the adapter command, which is
        // discarded, but a policy identified by externalId alone must still publish a usable id.
        form.setId(node.getId());
        UpdateResourceForm command = asNodeCommand(form, fields);
        List<NodeUpdateService.Target> targets = List.of(nodeUpdateService.authorize(command, node));
        nodeUpdateService.guardRenames(targets);
        // A NOT_OK verdict throws; a WARN verdict comes back as findings, and dropping them would
        // close only half the divergence — the resource and timeseries paths both record theirs.
        List<PolicyFinding> policyWarnings = nodeUpdateService.judgeNaming(targets);
        nodeUpdateService.apply(targets);

        // 2. THE POLICY'S OWN. Deactivation is a column on the node, so it is set here rather than smuggled through the
        // metadata map the caller supplied. Only when the caller actually asked: this assignment
        // used to be unconditional, and since the old DTO's flag was a primitive defaulting to
        // false, renaming a deactivated policy silently switched it back on.
        if (fields.getDeactivated().getSet() != null) {
            node.setDeactivated(fields.getDeactivated().getSet());
        }

        // 3. TEMPLATE HANDLING (moved from controller)
        String currentTemplateId = node.getMetadata().get("templateId");

        Long newTemplateId = fields.getTemplateId().getSet();

        boolean templateChanged =
                newTemplateId != null &&
                        (currentTemplateId == null || !currentTemplateId.equals(String.valueOf(newTemplateId)));

        if (templateChanged) {
            GovernanceTemplate template = governanceTemplateRepo.findById(newTemplateId)
                    .orElseThrow(() -> new RuntimeException("Template not found: " + newTemplateId));

            node.getMetadata().putAll(template.getMetadata());


            node.getMetadata().put("templateId", String.valueOf(newTemplateId));
        }

        // Validate the metadata the policy will actually have, not the fragment the caller sent:
        // with add/remove, a naming policy's pattern can now be broken by a request that never
        // mentions it. Runs inside the transaction, so a rejection rolls the mutations back.
        PolicyScopeValidator.validateNamingConfig(node.getMetadata());

        PolicyEntity saved = policyRepository.save(node);
        // Findings reference node_id, so they are recorded after the save that guarantees one.
        if (!policyWarnings.isEmpty()) {
            policyEnforcement.recordWarnings(policyWarnings, Map.of(saved.getExternalId(),
                    new PolicyEnforcement.WrittenEntity(saved.getId(), null)));
        }

        publishPolicyUpsert(saved);

        namingPolicyResolver.invalidate();

        return saved;
    }

    /**
     * The policy's shared field changes as the canonical node-update command.
     *
     * <p>{@code PolicyFields} and {@code ResourceFields} say the same thing about the five fields
     * every node has; only {@code deactivated} and {@code templateId} are the policy's own, and
     * those stay here. Adapting rather than widening the shared command is what keeps type-specific
     * fields out of it — the same shape {@code DataSetService} uses.
     */
    private static UpdateResourceForm asNodeCommand(UpdatePolicyForm form, PolicyFields fields) {
        UpdateResourceForm command = new UpdateResourceForm(form.getId());
        command.setExternalId(form.getExternalId());
        ResourceFields target = command.getUpdate();
        if (fields.getName().getSet() != null) {
            target.getName().set(fields.getName().getSet());
        }
        if (fields.getExternalId().getSet() != null) {
            target.getExternalId().set(fields.getExternalId().getSet());
        }
        if (fields.getDescription().getSet() != null) {
            target.getDescription().set(fields.getDescription().getSet());
        } else if (fields.getDescription().getSetNull()) {
            target.getDescription().setNull(true);
        }
        if (fields.getSource().getSet() != null) {
            target.getSource().set(fields.getSource().getSet());
        } else if (fields.getSource().getSetNull()) {
            target.getSource().setNull(true);
        }
        if (fields.getMetadata().getSet() != null) {
            target.getMetadata().setSet(fields.getMetadata().getSet());
        }
        if (fields.getMetadata().getAdd() != null) {
            target.getMetadata().add(fields.getMetadata().getAdd());
        }
        if (fields.getMetadata().getRemove() != null) {
            target.getMetadata().remove(fields.getMetadata().getRemove());
        }
        return command;
    }

    /** Replacing a required field with blank is a caller mistake, not a way to clear it. */
    private static String requireNonBlank(String value, String field) {
        if (value.isBlank()) {
            var error = new BadRequestError();
            error.setMessage("Policy " + field + " cannot be blank.");
            error.addFieldError(field, value);
            throw new BadRequestException(new ResponseError<BadRequestError>().setError(error));
        }
        return value;
    }

    /**
     * Re-assert a policy node in the graph after a write, so an edit does not leave Neo4j stale.
     *
     * <p><b>Why the action is CREATE for what is logically an update.</b> The Neo4j consumer's
     * {@code UPDATE} branch reads only {@code updateResourceForms}/{@code updateTimeseries} and
     * ignores {@code resources} entirely ({@code GraphEventNeo4jListener.updateResourceAndRelations}),
     * and the policy layer does not build those label-oriented forms. Its {@code createResource},
     * by contrast, is an idempotent MERGE-on-id upsert that re-asserts every node property — so a
     * CREATE matches the existing node rather than duplicating it, and actually applies the change.
     * Sending UPDATE here would be more honest and would silently do nothing.
     *
     * <p>Fixing that properly means teaching the consumer's UPDATE path to accept full resources
     * (or having policies build update forms); tracked in the audit backlog.
     */
    private void publishPolicyUpsert(PolicyEntity saved) {
        graphOutbox.queueUpsert(List.of(saved), List.of());
    }



    // 3. APPLY GOVERNANCE TEMPLATE
    @Transactional
    public NodeEntity updateTemplate(Long policyNodeId, Long templateId) {

        dataSecurity.assertCanManageDataSets();

        NodeEntity node = nodeRepository.getReferenceById(policyNodeId);

        GovernanceTemplate template = governanceTemplateRepo.findById(templateId)
                .orElseThrow(() -> new RuntimeException("Template not found: " + templateId));

        // Apply metadata from template
        node.getMetadata().putAll(template.getMetadata());
        node.getMetadata().put("templateId", String.valueOf(templateId));

        // Always mark which template was applied

        NodeEntity saved = nodeRepository.save(node);

        // Applying a template rewrites the node's metadata, so it is a node mutation like any
        // other and has to reach the graph. This emitted no CUD message at all, which left Neo4j
        // holding the pre-template metadata indefinitely.
        if (saved instanceof PolicyEntity policyEntity) {
            publishPolicyUpsert(policyEntity);
        }
        namingPolicyResolver.invalidate();

        return saved;
    }

    // 4. READ HELPERS
    @Transactional(readOnly = true)
    public PolicyResponseDTO toResponse(NodeEntity node) {
        Map<String, String> meta = node.getMetadata();

        return new PolicyResponseDTO(
                node.getId(),
                node.getLabels(),
                node.getNodeType().getName(),
                meta,
                node.getDateCreated()
        );
    }

    // 5. DELETE POLICY NODES USING RESOURCE PIPELINE
    @Transactional
    public void deletePolicies(DataWrapper<IdCollection> form) throws Exception {

        // ResourceService.delete re-checks per node, but state the policy rule here too so all five
        // policy mutations are gated in one visible place rather than one of them by delegation.
        dataSecurity.assertCanManageDataSets();

        GraphDataWrapper<Resource, EdgeProxy> toDelete = new GraphDataWrapper<>();

        // Convert IdCollection → Resource nodes (same as dataset delete)
        for (IdCollection idItem : form.getItems()) {
            Resource r = new Resource();

            if (idItem.getId() != null) {
                r.setId(idItem.getId());
                toDelete.getNodes().add(r);
            } else if (idItem.getExternalId() != null) {
                r.setExternalId(idItem.getExternalId());
                toDelete.getNodes().add(r);
            }
        }

        // Delegate to full deletion pipeline (Postgres + Neo4j + Pulsar)
        resourceService.delete(toDelete);

        namingPolicyResolver.invalidate();
    }


    @Transactional(readOnly = true)
    public List<Policy> listAllPolicies() {
        List<PolicyEntity> nodes = policyRepository.findAll();

        return nodes.stream()
                .map(PolicyTransformer::toPolicy)
                .toList();
    }

    @Transactional(readOnly = true)
    public PolicyEntity getPolicyById(Long policyNodeId) {
        return policyRepository.findById(policyNodeId)
                .orElseThrow(() -> new ObjectNotFoundException("Policy node not found: " + policyNodeId));
    }

}
