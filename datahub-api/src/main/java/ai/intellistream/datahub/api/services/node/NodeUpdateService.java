// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.services.node;

import ai.intellistream.datahub.api.controllers.errors.BadRequestError;
import ai.intellistream.datahub.api.controllers.errors.BadRequestException;
import ai.intellistream.datahub.api.datasecurity.DataSecurity;
import ai.intellistream.datahub.api.policy.PolicyCandidate;
import ai.intellistream.datahub.api.policy.PolicyEnforcement;
import ai.intellistream.datahub.errors.ResponseError;
import ai.intellistream.datahub.helpers.text.ExternalIds;
import ai.intellistream.datahub.jpa.domains.AssetEntity;
import ai.intellistream.datahub.jpa.domains.DatasetEntity;
import ai.intellistream.datahub.jpa.domains.NodeEntity;
import ai.intellistream.datahub.jpa.domains.PolicyEntity;
import ai.intellistream.datahub.models.UpdateResourceForm;
import ai.intellistream.datahub.models.policy.PolicyFinding;
import ai.intellistream.datahub.models.validation.ResourceFields;
import ai.intellistream.datahub.repositories.node.DataSetRepository;
import ai.intellistream.datahub.repositories.node.NodeRepository;
import ai.intellistream.datahub.services.LabelService;
import ai.intellistream.datahub.services.NodeService;
import lombok.RequiredArgsConstructor;
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
@RequiredArgsConstructor
public class NodeUpdateService {

    private final NodeRepository nodeRepository;
    private final DataSetRepository dataSetRepository;
    private final DataSecurity dataSecurity;
    private final LabelService labelService;
    private final NodeService nodeService;
    private final PolicyEnforcement policyEnforcement;

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
        Map<String, String> metadata = resource.getMetadata();
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
        if (resource instanceof AssetEntity asset){
            if(fields.getGeoLocation().getSet() != null){
                asset.setGeoLocation(fields.getGeoLocation().getSet().getJson());
            }
            if(fields.getGeoLocation().getSetNull()){
                asset.setGeoLocation(null);
            }
        }


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
