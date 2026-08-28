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
import ai.intellistream.datahub.errors.ObjectNotFoundException;
import ai.intellistream.datahub.helpers.utils.IdGenerator;
import ai.intellistream.datahub.jpa.domains.*;
import ai.intellistream.datahub.jpa.dto.PolicyResponseDTO;
import ai.intellistream.datahub.models.EdgeProxy;
import ai.intellistream.datahub.models.IdCollection;
import ai.intellistream.datahub.models.Policy;
import ai.intellistream.datahub.models.Resource;
import ai.intellistream.datahub.repositories.governance.GovernanceTemplateRepository;
import ai.intellistream.datahub.repositories.node.EdgeRepository;
import ai.intellistream.datahub.repositories.node.NodeRepository;
import ai.intellistream.datahub.repositories.node.NodeTypeRepository;
import ai.intellistream.datahub.repositories.node.RelationshipTypeRepository;
import ai.intellistream.datahub.services.NodeService;
import ai.intellistream.datahub.transformers.PolicyTransformer;
import ai.intellistream.datahub.transformers.ResourceTransformer;
import ai.intellistream.datahub.transformers.EdgeProxyTransformer;
import ai.intellistream.datahub.models.RelForm;
import ai.intellistream.datahub.pulsar.EventAction;
import ai.intellistream.datahub.pulsar.EventObject;
import ai.intellistream.datahub.pulsar.ResourceCudMessage;
import ai.intellistream.datahub.tenant.TenantContext;
import ai.intellistream.datahub.api.messaging.events.ResourceCudPublishEvent;
import org.springframework.context.ApplicationEventPublisher;
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
    private final NodeTypeRepository nodeTypeRepository;
    private final EdgeRepository edgeRepository;
    private final RelationshipTypeRepository relationshipTypeRepository;
    private final NodeService nodeService;
    private final ResourceService resourceService;
    private final GovernanceTemplateRepository governanceTemplateRepo;
    private final ai.intellistream.datahub.repositories.node.PolicyRepository policyRepository;
    private final ApplicationEventPublisher applicationEventPublisher;

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

    // 1. CREATE NEW EMPTY POLICY NODE
    @Transactional
    public PolicyEntity createEmptyPolicy(String name, Long templateId, String externalId, Long dataSetId, String description, Map<String, String> metadata) {

        dataSecurity.assertCanManageDataSets();

        PolicyEntity policyNode = new PolicyEntity();

        // `name` is the display name; the graph identifies a policy by the "POLICY" label
        // (graph-network.js keys off labels.includes("POLICY") to render it and open the
        // policy editor), so the label must be POLICY — not the name.
        String nodeName = (name != null && !name.isBlank()) ? name : "Policy";
        policyNode.setName(nodeName);
        // Through applyLabelNames, so the POLICY label row and the node_labels link are written too.
        // Setting the labels string alone left every policy reporting labels: ["POLICY"] while
        // matching no label filter — and in a tenant whose policies were all created here, no POLICY
        // label row existed at all.
        nodeService.applyLabelNames(policyNode, List.of(TypeLabels.POLICY));

        // externalId is user-defined or a random UUIDv7 fallback. setExternalId normalizes the
        // value AND derives externalIdHash from that normalized form — do NOT also set the hash
        // from the raw string, or a later lookup by external id would never match the stored row.
        String effectiveExternalId = (externalId != null && !externalId.isBlank())
                ? externalId
                : IdGenerator.getRandomUUID7AsString();
        policyNode.setExternalId(effectiveExternalId);

        // Persist the optional description from the form's Advanced section. Was silently dropped
        // before (this method never received it), so a created policy's description always read
        // back null even though the form sent one. The update path already persists it.
        if (description != null && !description.isBlank()) {
            policyNode.setDescription(description);
        }

        if (templateId != null) {
            policyNode.getMetadata().put("templateId", String.valueOf(templateId));
        }
        // Persist the form-supplied policy config (kind + params) verbatim. Inert for now — the
        // graph just carries it (createResource flattens each key to a metadata_<key> node prop).
        if (metadata != null && !metadata.isEmpty()) {
            policyNode.getMetadata().putAll(metadata);
        }

        nodeRepository.save(policyNode);
        nodeRepository.flush();

        // Optionally attach the policy to a dataset via a ENFORCED_ON edge, so it shows up
        // connected in the node network. Reuses ResourceService.mapEdge (which find-or-creates the
        // relationship type) exactly like resource creation does.
        List<EdgeEntity> edges = new ArrayList<>();
        if (dataSetId != null) {
            RelForm relForm = new RelForm();
            relForm.setFromId(dataSetId);
            relForm.setToId(policyNode.getId());
            relForm.setRelationshipType("ENFORCED_ON");
            relForm.setDataSetId(dataSetId);
            edges.add(edgeRepository.save(resourceService.mapEdge(new EdgeEntity(), relForm)));
        }

        // Queue the policy node (and its edge) for the graph mirror the same way
        // ResourceService.create does, so they actually enter the knowledge graph instead of
        // living only in Postgres.
        List<EdgeProxy> edgeProxies = edges.stream().map(EdgeProxyTransformer::fromEdgeEntity).toList();
        Resource policyProxy = ResourceTransformer.from(policyNode, edgeProxies);
        var msg = new ResourceCudMessage(EventAction.CREATE, EventObject.RESOURCE_AND_RELATION, TenantContext.getTenantId());
        msg.setResources(List.of(policyProxy));
        msg.setEdges(edgeProxies);
        applicationEventPublisher.publishEvent(new ResourceCudPublishEvent(msg));

        namingPolicyResolver.invalidate();

        return policyNode;
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

        dataSecurity.assertCanManageDataSets();

        boolean hasExternalId = form.getExternalId() != null && !form.getExternalId().isBlank();
        if (form.getId() == null && !hasExternalId) {
            throw new IllegalArgumentException("Policy id or externalId is required for update");
        }

        // Identify by either, like every other update endpoint. A null id simply never matches, so
        // the OR resolves to whichever the caller supplied.
        PolicyEntity node = policyRepository
                .findByIdOrExternalId(form.getId(), hasExternalId ? form.getExternalId() : null)
                .orElseThrow(() -> new ObjectNotFoundException("Policy node not found: "
                        + (form.getId() != null ? form.getId() : form.getExternalId())));

        // An item naming a policy but carrying no changes is a no-op, not a null dereference.
        PolicyFields fields = form.getUpdate() != null ? form.getUpdate() : new PolicyFields();

        // 1. BASIC FIELDS. Throughout: `set` wins over `setNull`, so a value the caller supplied is
        // never discarded by a stray clear on the same field.
        if (fields.getName().getSet() != null) {
            node.setName(requireNonBlank(fields.getName().getSet(), "name"));
        }

        if (fields.getExternalId().getSet() != null) {
            // setExternalId normalizes and re-derives externalIdHash; setting the hash from the
            // raw string here would desync it from the stored (normalized) external_id.
            node.setExternalId(requireNonBlank(fields.getExternalId().getSet(), "externalId"));
        }

        if (fields.getDescription().getSet() != null) {
            node.setDescription(fields.getDescription().getSet());
        } else if (fields.getDescription().getSetNull()) {
            node.setDescription(null);
        }

        if (fields.getSource().getSet() != null) {
            node.setSource(fields.getSource().getSet());
        } else if (fields.getSource().getSetNull()) {
            node.setSource(null);
        }

        // 2. METADATA (activePolicies, availablePolicies, etc.). set/add/remove, like every other
        // metadata map — the old putAll-only form could add and overwrite keys but never drop one,
        // so shrinking activePolicies was impossible and stale keys stayed forever.
        if (fields.getMetadata().getSet() != null) {
            node.setMetadata(new HashMap<>(fields.getMetadata().getSet()));
        }
        if (fields.getMetadata().getAdd() != null) {
            node.getMetadata().putAll(fields.getMetadata().getAdd());
        }
        if (fields.getMetadata().getRemove() != null) {
            node.getMetadata().keySet().removeAll(fields.getMetadata().getRemove());
        }

        // Deactivation is a column on the node, so it is set here rather than smuggled through the
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

        publishPolicyUpsert(saved);

        namingPolicyResolver.invalidate();

        return saved;
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
        Resource policyProxy = ResourceTransformer.from(saved, List.<EdgeProxy>of());
        var msg = new ResourceCudMessage(EventAction.CREATE, EventObject.RESOURCE_AND_RELATION, TenantContext.getTenantId());
        msg.setResources(List.of(policyProxy));
        msg.setEdges(List.<EdgeProxy>of());
        applicationEventPublisher.publishEvent(new ResourceCudPublishEvent(msg));
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
