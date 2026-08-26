// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.services.node;

import ai.intellistream.datahub.api.controllers.errors.DuplicateDataException;
import ai.intellistream.datahub.api.controllers.errors.DuplicateError;
import java.util.Objects;
import ai.intellistream.datahub.api.controllers.errors.BadRequestError;
import ai.intellistream.datahub.api.controllers.errors.BadRequestException;
import ai.intellistream.datahub.api.datasecurity.DataSecurity;
import ai.intellistream.datahub.api.policy.PolicyCandidate;
import ai.intellistream.datahub.api.policy.PolicyEnforcement;
import ai.intellistream.datahub.errors.ResponseError;
import ai.intellistream.datahub.helpers.text.ExternalIds;
import ai.intellistream.datahub.jpa.domains.DatasetEntity;
import ai.intellistream.datahub.jpa.domains.NodeEntity;
import java.util.Set;
import ai.intellistream.datahub.jpa.domains.TimeseriesEntity;
import ai.intellistream.datahub.jpa.domains.ResourceEntity;
import ai.intellistream.datahub.jpa.domains.FunctionEntity;
import ai.intellistream.datahub.jpa.domains.AssetEntity;
import ai.intellistream.datahub.jpa.domains.PolicyEntity;
import ai.intellistream.datahub.models.UpdateResourceForm;
import ai.intellistream.datahub.models.policy.PolicyFinding;
import ai.intellistream.datahub.models.validation.ResourceFields;
import ai.intellistream.datahub.repositories.node.DataSetRepository;
import ai.intellistream.datahub.repositories.node.NodeRepository;
import ai.intellistream.datahub.services.LabelService;
import ai.intellistream.datahub.services.NodeService;
import org.hibernate.Hibernate;
import org.springframework.stereotype.Component;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * The node-update pipeline: one ordered sequence every node update runs through, whatever kind of
 * node it is.
 *
 * <p>Extracted from {@code ResourceService.update} so the cross-cutting steps are named and owned
 * in one place instead of being re-derived per type — {@code PolicyService.updatePolicyNode} and
 * {@code TimeseriesService.updateTimeseries} each implement their own version today, which is how
 * they came to disagree about whether a rename is judged against the naming policy. The stages:
 *
 * <ol>
 *   <li>{@link #resolveAndAuthorize} — id/externalId to a managed entity, then the ACL. Mutates
 *       nothing, so a denial or a miss costs no writes.</li>
 *   <li>{@link #judgeNaming} — the naming policy, over the whole batch, before anything is
 *       written.</li>
 *   <li>{@link #apply} — the only per-type step. Phase 2 of NODE_UPDATE_REFACTOR.md turns this
 *       into a strategy registry; today it is one method with an {@code instanceof} for the
 *       asset-only geolocation.</li>
 * </ol>
 *
 * <p>The order is the point, and it is not arbitrary: resolve, then judge, then apply. Applying
 * before judging would write the new external id onto the managed entity, after which Hibernate
 * may auto-flush ahead of the policy's own query and persist a value it was about to reject.
 *
 * <p>Persisting, edge handling and event emission stay with the caller for now; the engine takes
 * them over in later phases, when the other two services fold in.
 */
@Component
public class NodeUpdateService {

    private final NodeRepository nodeRepository;
    private final DataSetRepository dataSetRepository;
    private final DataSecurity dataSecurity;
    private final LabelService labelService;
    private final NodeService nodeService;
    private final PolicyEnforcement policyEnforcement;

    /**
     * Every node type this pipeline knows how to update, and what each adds to the shared stages.
     *
     * <p><b>Fixed, and exhaustive on purpose.</b> Discovering strategies from the container would
     * make "no strategy" and "type nobody has thought about" indistinguishable — both would sail
     * through the shared stages and silently drop whatever that type actually needed. Listing all
     * six here means a seventh entity type fails loudly on its first update (see
     * {@link #strategyFor}) rather than being quietly half-applied, and
     * {@code NodeUpdateStrategyRegistryTest} fails the moment the entity family and this map
     * disagree.
     *
     * <p>{@link NodeUpdateStrategy#NONE} is a real answer, not a gap: those types genuinely have
     * no field beyond the shared set, and saying so is what distinguishes them from an omission.
     */
    private static final Map<Class<? extends NodeEntity>, NodeUpdateStrategy> STRATEGIES = Map.of(
            AssetEntity.class, new AssetUpdateStrategy(),
            ResourceEntity.class, NodeUpdateStrategy.NONE,
            DatasetEntity.class, NodeUpdateStrategy.NONE,
            PolicyEntity.class, NodeUpdateStrategy.NONE,
            FunctionEntity.class, NodeUpdateStrategy.NONE,
            TimeseriesEntity.class, NodeUpdateStrategy.NONE);

    public NodeUpdateService(NodeRepository nodeRepository,
                             DataSetRepository dataSetRepository,
                             DataSecurity dataSecurity,
                             LabelService labelService,
                             NodeService nodeService,
                             PolicyEnforcement policyEnforcement) {
        this.nodeRepository = nodeRepository;
        this.dataSetRepository = dataSetRepository;
        this.dataSecurity = dataSecurity;
        this.labelService = labelService;
        this.nodeService = nodeService;
        this.policyEnforcement = policyEnforcement;
    }

    /** A resolved update: the caller's requested changes, paired with the managed entity. */
    public record Target(UpdateResourceForm form, NodeEntity entity) {}

    /**
     * Stage 1 and 2. Resolve every target and authorize it, <em>without mutating any of them</em>,
     * so the batch can still be rejected whole.
     */
    public List<Target> resolveAndAuthorize(Collection<UpdateResourceForm> forms) {
        ResponseError<BadRequestError> errors = new ResponseError<>();
        List<Target> targets = new ArrayList<>();
        for (UpdateResourceForm form : forms) {
            Long id = form.getId();
            String externalId = form.getExternalId();
            if (id == null && externalId == null) {
                var de = new BadRequestError();
                de.setMessage("Missing both id and externalId, asset cannot be found.");
                de.getFields().add(Map.of("externalId", "null", "id", "null"));
                errors.setError(de);
                throw new BadRequestException(errors);
            }
            NodeEntity resource = id != null
                    ? nodeRepository.findById(id).orElse(null)
                    : nodeRepository.findByExternalIdHash(ExternalIds.hash(externalId));

            if (resource == null) {
                var de = new BadRequestError();
                de.setMessage("Resource cannot be found.");
                de.getFields().add(Map.of("externalId", String.valueOf(externalId), "id", String.valueOf(id)));
                errors.setError(de);
                throw new BadRequestException(errors);
            }
            // Must be able to write the resource's current dataset before mutating it.
            dataSecurity.assertCanWrite(resource);
            // Mutating a dataset or policy node additionally needs the manage grant. Stated
            // explicitly rather than inherited from those nodes being orphans (see
            // DataSecurity#canManageDataSets on that coincidence) — an orphan-based rule would
            // miss a dataset/policy node that was minted carrying a data_set_id.
            if (resource instanceof DatasetEntity || resource instanceof PolicyEntity) {
                dataSecurity.assertCanManageDataSets();
            }
            // Functions are plain datastore nodes now — editable like any resource.
            targets.add(new Target(form, resource));
        }
        if (errors.getError() != null && !errors.getError().getFields().isEmpty()) {
            throw new BadRequestException(errors);
        }
        return targets;
    }

    /**
     * The strategy for this node. Never null: an unregistered type is a programming error, not a
     * node with nothing to do, and it throws rather than updating a row half-way.
     *
     * <p>Resolves to the nearest registered ancestor rather than the exact class, because the
     * runtime class is often not the mapped one — {@code Hibernate.getClass} unwraps a lazy proxy,
     * but a test double or a future refinement of a type would otherwise miss its strategy and
     * silently drop that type's fields. Walking stops at {@code NodeEntity}, so an unknown type
     * cannot inherit some other type's mapping by accident.
     */
    private NodeUpdateStrategy strategyFor(NodeEntity node) {
        Class<?> type = Hibernate.getClass(node);
        while (type != null && NodeEntity.class.isAssignableFrom(type)) {
            NodeUpdateStrategy strategy = STRATEGIES.get(type);
            if (strategy != null) {
                return strategy;
            }
            type = type.getSuperclass();
        }
        throw new IllegalStateException(
                "No update strategy registered for node type " + Hibernate.getClass(node).getName()
                        + ". Add it to NodeUpdateService.STRATEGIES — use NodeUpdateStrategy.NONE if "
                        + "it has no fields beyond the shared ones. Refusing to update it "
                        + "half-applied.");
    }

    /** The node types this pipeline can update. Exposed so a test can hold it to the entity family. */
    public static Set<Class<? extends NodeEntity>> registeredTypes() {
        return STRATEGIES.keySet();
    }

    /**
     * Stage 2 alone, for a caller that resolved its own target.
     *
     * <p>A typed endpoint scopes its lookup to its own type — {@code /policies} asks the policy
     * repository, so an id naming some other kind of node is "no such policy", a 404, rather than
     * the generic "resource cannot be found". Re-resolving it here would flatten that distinction,
     * so the caller keeps its lookup and hands the entity over for the parts it should not be
     * deciding for itself.
     */
    public Target authorize(UpdateResourceForm form, NodeEntity entity) {
        dataSecurity.assertCanWrite(entity);
        if (entity instanceof DatasetEntity || entity instanceof PolicyEntity) {
            dataSecurity.assertCanManageDataSets();
        }
        return new Target(form, entity);
    }

    /**
     * Stage 3. Refuse a rename that would collide with an external id already in use.
     *
     * <p>Not a type-specific rule, though only the timeseries path used to apply it: the unique
     * index spans the whole {@code node} table, so a resource renamed onto a dataset's external id
     * collides just as surely. Without this the clash reaches the database and surfaces as a
     * constraint violation — a 500 for what is plainly a caller mistake — whereas the timeseries
     * endpoint answered a clean 409. Now every node type gets the 409.
     *
     * <p>Checked over the whole batch before anything is applied, because two renames onto the
     * same id within one request would each pass a per-item check (neither is in the table yet)
     * and only die on the constraint.
     */
    public void guardRenames(List<Target> targets) {
        Map<Long, String> withinBatch = new java.util.HashMap<>();
        List<Map<String, String>> collisions = new ArrayList<>();
        for (Target target : targets) {
            ResourceFields fields = target.form().getUpdate();
            String renamed = fields == null ? null : fields.getExternalId().getSet();
            if (renamed == null || renamed.equals(target.entity().getExternalId())) {
                continue;   // no rename, or a rename to what it already is
            }
            long hash = ExternalIds.hash(renamed);
            if (withinBatch.put(hash, renamed) != null) {
                collisions.add(Map.of("externalId", renamed));
                continue;
            }
            NodeEntity clash = nodeRepository.findByExternalIdHash(hash);
            if (clash != null && !Objects.equals(clash.getId(), target.entity().getId())) {
                collisions.add(Map.of("externalId", renamed));
            }
        }
        if (!collisions.isEmpty()) {
            var error = new DuplicateError();
            error.setCode(409);
            error.setMessage("A node with that externalId already exists.");
            error.setDuplicated(collisions);
            throw new DuplicateDataException(new ResponseError<DuplicateError>().setError(error));
        }
    }

    /**
     * Stage 3. Judge the whole batch against the naming policy before a single entity is touched.
     * Only ids that actually change are judged — see {@link #namingCandidatesForUpdate}.
     */
    public List<PolicyFinding> judgeNaming(List<Target> targets) {
        return policyEnforcement.check(namingCandidatesForUpdate(targets));
    }

    /** Stage 4. Apply every target's field changes. Nothing before this line has mutated anything. */
    public List<NodeEntity> apply(List<Target> targets) {
        List<NodeEntity> applied = new ArrayList<>(targets.size());
        for (Target target : targets) {
            applied.add(updateNode(target.entity(), target.form()));
        }
        return applied;
    }

    public NodeEntity updateNode(NodeEntity resource, UpdateResourceForm form) {
        ResponseError<BadRequestError> errors = new ResponseError<>();
        if(!form.getUpdate().validateFields()){
            errors.setError(new BadRequestError());
            form.getUpdate().getErrors().forEach( error -> {
                errors.getError().addFieldError(error.getObjectName(), error.getDefaultMessage());
            });
            throw new BadRequestException(errors);
        }

        ResourceFields fields = form.getUpdate();
        resource.setLastUpdated(ZonedDateTime.now());

        // Update externalId
        if(fields.getExternalId().getSet() != null){
            String newExternalId = fields.getExternalId().getSet();
            if(form.getExternalId() == null){
                form.setExternalId(resource.getExternalId());
            }
            resource.setExternalId(newExternalId);
        }

        // Update name field
        if(fields.getName().getSet() != null){
            resource.setName(fields.getName().getSet());
        }

        /**
         * Update metadata
         * If key found, update metadata value in existing entry,
         * If key not found, add entry
         * If remove, delete metadata entry
         */
        if(fields.getMetadata().getSet() != null){
            resource.setMetadata(fields.getMetadata().getSet());
        }

        if(fields.getMetadata().getAdd() != null){
            resource.getMetadata().putAll(fields.getMetadata().getAdd());
        }
        if(fields.getMetadata().getRemove() != null ){
            resource.getMetadata().keySet().removeAll(fields.getMetadata().getRemove());
        }

        // Update description field
        if(fields.getDescription().getSet() != null){
            resource.setDescription(fields.getDescription().getSet());
        }
        if(fields.getDescription().getSetNull()){
            resource.setDescription(null);
        }
        // Update source field (common to all node types)
        if(fields.getSource().getSet() != null){
            resource.setSource(fields.getSource().getSet());
        }
        if(fields.getSource().getSetNull() ){
            resource.setSource(null);
        }
        // The only per-type step.
        strategyFor(resource).apply(resource, fields);


        // Update labels. A node's type-label (ASSET/DATASET/POLICY/TIMESERIES/FUNCTION) is intrinsic
        // and immutable — resolveLabelUpdate returns the new label names with this node's type-label
        // enforced (empty when no label change was requested; see LabelService#resolveLabelUpdate).
        // Apply the same names to both Postgres representations so they can't drift: the labels string
        // and the M2M labelEntities (the latter is what the label-in-use check reads via Label.nodes).
        labelService.resolveLabelUpdate(resource, fields.getLabels())
                .ifPresent(labelNames -> nodeService.applyLabelNames(resource, labelNames));

        // Update dataset id field
        if(fields.getDataSetId().getSet() != null){
            Long dataSetId = fields.getDataSetId().getSet();
            // Moving a resource into a dataset also requires write access to the target.
            dataSecurity.assertCanWriteDataSet(dataSetId);
            DatasetEntity dataSet = dataSetRepository.findById(dataSetId).orElseThrow(()->{
                var de = new BadRequestError();
                de.setMessage("DataSet cannot be found.");
                de.getFields().add(Map.of("DataSet.Id", String.valueOf(dataSetId)));
                errors.setError(de);
                return new BadRequestException(errors);
            });
            resource.setDataSet(dataSet);
        }
        if(fields.getDataSetId().getSetNull()){
            resource.setDataSet(null);
        }

        return resource;
    }

    private static List<PolicyCandidate> namingCandidatesForUpdate(List<Target> targets) {
        List<PolicyCandidate> candidates = new ArrayList<>();
        int index = 0;
        for (Target target : targets) {
            UpdateResourceForm form = target.form();
            NodeEntity entity = target.entity();
            String newExternalId = form.getUpdate() == null ? null : form.getUpdate().getExternalId().getSet();
            if (newExternalId != null) {
                // The incoming name if the same request renames it, else the stored one — a
                // suggestion should be derived from the name the entity will actually have.
                String newName = form.getUpdate().getName().getSet();
                candidates.add(PolicyCandidate.forUpdate(
                        index, newExternalId,
                        newName != null ? newName : entity.getName(),
                        entity.getDataSet() == null ? null : entity.getDataSet().getId(),
                        entity.getId(), entity.getExternalId()));
            }
            index++;
        }
        return candidates;
    }
}
