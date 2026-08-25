// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.services;

import ai.intellistream.datahub.errors.FieldError;
import ai.intellistream.datahub.errors.InvalidResourceException;
import ai.intellistream.datahub.errors.ResponseError;
import ai.intellistream.datahub.jpa.domains.*;
import ai.intellistream.datahub.label.LabelForm;
import ai.intellistream.datahub.models.Resource;
import ai.intellistream.datahub.repositories.node.DataSetRepository;

import ai.intellistream.datahub.models.NodeModel;
import ai.intellistream.datahub.timeseries.Timeseries;
import ai.intellistream.datahub.timeseries.enums.TableEngine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.w3c.dom.Node;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
@Slf4j
public class NodeService {


    private final LabelService labelService;

    private final DataSetRepository dataSetRepository;

    public NodeService(
            LabelService labelService,
            DataSetRepository dataSetRepository) {
        this.labelService = labelService;
        this.dataSetRepository = dataSetRepository;
    }

    /**
     * Write both Postgres representations of a node's labels from one resolved list: the
     * denormalised {@code labels} string and the {@code node_labels} M2M rows. Only the latter is
     * what the label filter's hash join and the label-in-use check read, so a writer that sets the
     * string alone produces a node that reports its labels on every read and matches none of them.
     *
     * <p>This exists because that is exactly what {@code PolicyService.createEmptyPolicy} did — the
     * two representations were kept in step by hand at six sites and the seventh forgot.
     */
    public void applyLabels(NodeEntity node, Collection<Label> labels) {
        node.setLabels(labels.stream().map(Label::getName).collect(Collectors.joining(",")));
        // Mutable: findAllAndCreateFromNames returns an immutable list and Hibernate's merge clears
        // the M2M collection in place.
        node.setLabelEntities(new ArrayList<>(labels));
    }

    /**
     * {@link #applyLabels} for a caller holding names rather than resolved labels, creating any
     * label that does not exist yet — the same find-or-create the resource API uses.
     */
    public void applyLabelNames(NodeEntity node, List<String> labelNames) {
        applyLabels(node, labelService.findAllAndCreateFromNames(labelNames));
    }

    /**
     * Bind AssetForm into AssetNode
     *
     * @param resource
     * @return AssetNode
     */

    @Transactional
    public NodeEntity createFromResource(Resource resource) throws InvalidResourceException {
        try {
            List<Label> labels = labelService.findAllAndCreateFromNames(resource.getLabels());
            List<String> labelNames = labels.stream().map(Label::getName).toList();

            // A node's type is intrinsic: it may carry at most one type-label. Reject ambiguous input
            // rather than silently letting a dispatch order pick a winner.
            long typeLabelCount = labelNames.stream().filter(TypeLabels::isTypeLabel).distinct().count();
            if (typeLabelCount > 1) {
                throw invalidResource("A node may have at most one type-label (one of " + TypeLabels.ALL + ").");
            }
            // TypeLabels.CREATABLE is the single authority for which types this API may mint
            // (TIMESERIES is created through its own API today).
            Optional<String> nonCreatable = labelNames.stream()
                    .filter(TypeLabels::isTypeLabel)
                    .filter(name -> !TypeLabels.CREATABLE.contains(name))
                    .findFirst();
            if (nonCreatable.isPresent()) {
                throw invalidResource("Not allowed to create " + nonCreatable.get() + " using the resource api!");
            }

            NodeEntity node;
            if (labelNames.contains(TypeLabels.ASSET)) {
                AssetEntity asset = new AssetEntity();
                asset.setGeoLocation(resource.getGeoLocation() == null ? null : resource.getGeoLocation().getJson());
                node = asset;
            } else if (labelNames.contains(TypeLabels.FUNCTION)) {
                node = new FunctionEntity();
            } else if (labelNames.contains(TypeLabels.DATASET)) {
                node = new DatasetEntity();
            } else if (labelNames.contains(TypeLabels.POLICY)) {
                node = new PolicyEntity();
            } else {
                node = new ResourceEntity();
            }
            applyLabels(node, labels);
            mapCommonNodeFields(node, resource);
            // isRoot is not a NodeModel primitive (it lives on Resource/Asset), so it is applied
            // here rather than in the shared mapper.
            node.setIsRoot(resource.getIsRoot());
            return node;
        } catch(InvalidResourceException e) {
            throw e;
        } catch (Exception e){
            log.error(e.getMessage(), e);
            throw e;
        }
    }

    private static InvalidResourceException invalidResource(String message) {
        ResponseError<FieldError> error = new ResponseError<>();
        var fieldError = new FieldError();
        fieldError.setErrorMessage(message);
        error.setError(fieldError);
        return new InvalidResourceException(error);
    }
    /**
     * Copies the fields every node shares (the {@code NodeModel} primitives) onto the entity —
     * with one deliberate exception: dataset and policy entities never take a {@code data_set_id}.
     * Their lifecycle is dataset management, and the orphan shape (null {@code data_set_id}) is
     * what the ACL's write-everything fallback keys on; populating it would leave a managed node
     * mutable under a per-dataset grant (see POLICY_DATASETID_BUG.md and
     * {@code DataSecurity#canManageDataSets}). {@code isRoot} is not a base primitive and stays
     * with the callers whose types have it.
     */
    public void mapCommonNodeFields(NodeEntity node, NodeModel form){
        node.setName(form.getName());
        node.setDescription(form.getDescription());
        node.setSource(form.getSource());
        node.setExternalId(form.getExternalId());
        if(form.getCreatedTime() != null){
            node.setDateCreated(form.getCreatedTime());
        }
        if(form.getLastUpdatedTime() != null){
            node.setLastUpdated(form.getLastUpdatedTime());
        }
        if(form.getDataSetId() != null
                && !(node instanceof DatasetEntity) && !(node instanceof PolicyEntity)){
            node.setDataSet(dataSetRepository.getReferenceById(form.getDataSetId()));
        }
        node.setMetadata(form.getMetadata());
    }
    @Transactional
    public Collection<TimeseriesEntity> mapTimeseriesFrom(Collection<Timeseries> timeseriesCollection){
        Collection<TimeseriesEntity> nodes = new ArrayList<>();
        for(var ts : timeseriesCollection){
            nodes.add( mapNewNodeFromTimeseries(ts) );
        }
        return nodes;
    }

    @Transactional
    public TimeseriesEntity mapNewNodeFromTimeseries(Timeseries ts){
        TimeseriesEntity node = new TimeseriesEntity();
        // Resolve whatever labels the caller sent, not just the type-label. This used to build a
        // single hardcoded TIMESERIES LabelForm, so a create that carried extra domain labels
        // had them silently dropped — the one node type that could not be labelled on create.
        // Timeseries.setLabels (via NodeModel) already guarantees TIMESERIES is in the list, so
        // the type-label cannot be lost by going through the caller's set.
        applyLabels(node, labelService.findAllAndCreateFromNames(new ArrayList<>(ts.getLabels())));

        node.setValueType(ts.getValueType());
        node.setUnit(ts.getUnit());
        node.setUnitExternalId(ts.getUnitExternalId());
        node.setTableEngine(TableEngine.MERGETREE);
        // Timeseries IS a NodeModel, so the shared mapper covers name/externalId/source/dataset/
        // metadata/timestamps. No catch-and-return-null: a mapping failure must fail the create,
        // not surface later as a null element in saveAll.
        mapCommonNodeFields(node, ts);
        return node;
    }




}
