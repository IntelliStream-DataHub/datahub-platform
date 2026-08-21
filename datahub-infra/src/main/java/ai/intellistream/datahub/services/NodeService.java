// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.services;

import ai.intellistream.datahub.errors.FieldError;
import ai.intellistream.datahub.errors.InvalidResourceException;
import ai.intellistream.datahub.errors.ResponseError;
import ai.intellistream.datahub.jpa.domains.*;
import ai.intellistream.datahub.label.LabelForm;
import ai.intellistream.datahub.models.Resource;
import ai.intellistream.datahub.repositories.node.DataSetRepository;

import ai.intellistream.datahub.resource.NodeForm;
import ai.intellistream.datahub.timeseries.Timeseries;
import ai.intellistream.datahub.timeseries.enums.TableEngine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.w3c.dom.Node;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
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
            // rather than silently letting the dispatch order below pick a winner.
            long typeLabelCount = labelNames.stream().filter(TypeLabels::isTypeLabel).distinct().count();
            if (typeLabelCount > 1) {
                ResponseError<FieldError> error = new ResponseError<>();
                var fieldError = new FieldError();
                fieldError.setErrorMessage("A node may have at most one type-label (one of " + TypeLabels.ALL + ").");
                error.setError(fieldError);
                throw new InvalidResourceException(error);
            }

            if (labelNames.contains(TypeLabels.ASSET)) {
                AssetEntity node = new AssetEntity();
                applyLabels(node, labels);
                node.setGeoLocation(resource.getGeoLocation() == null ? null : resource.getGeoLocation().getJson());
                mapCommonNodeFields(node, resource);
                return node;
            } else if (labelNames.contains(TypeLabels.TIMESERIES)) {
                // You are not allowed to create Timeseries entities using the resource api
                ResponseError<FieldError> error = new ResponseError<>();
                var fieldError = new FieldError();
                fieldError.setErrorMessage("Not allowed to create Time Series using the resource api!");
                error.setError(fieldError);
                throw new InvalidResourceException(error);
            } else if (labelNames.contains(TypeLabels.FUNCTION)) {
                FunctionEntity node = new FunctionEntity();
                applyLabels(node, labels);
                mapCommonNodeFields(node, resource);
                return node;
            } else if (labelNames.contains(TypeLabels.DATASET)) {
                DatasetEntity node = new DatasetEntity();
                applyLabels(node, labels);
                mapCommonNodeFields(node, resource);
                return node;
            } else if (labelNames.contains(TypeLabels.POLICY)) {
                PolicyEntity node = new PolicyEntity();
                applyLabels(node, labels);
                mapCommonNodeFields(node, resource);
                return node;
            } else {
                ResourceEntity node = new ResourceEntity();
                applyLabels(node, labels);
                mapCommonNodeFields(node, resource);
                return node;
            }
        } catch(InvalidResourceException e) {
            throw e;
        } catch (Exception e){
            log.error(e.getMessage(), e);
            throw e;
        }


    }
    public void mapCommonNodeFields(NodeEntity node, Resource form){
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
        node.setIsRoot(form.getIsRoot());
        if(form.getDataSetId() != null){
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
        try{
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
            node.setSource(ts.getSource());
            mapNode(node, ts.getNodeForm());
            return node;
        } catch (Exception e){
            log.error(e.getMessage(), e);
        }

        return null;
    }

    @Transactional
    protected void mapNode(NodeEntity node, NodeForm form){
        try{

            node.setName(form.getName());
            node.setDescription(form.getDescription());
            node.setExternalId(form.getExternalId());
            if(form.getCreatedTime() != null){
                node.setDateCreated(form.getCreatedTime());
            }
            if(form.getLastUpdatedTime() != null){
                node.setLastUpdated(form.getLastUpdatedTime());
            }

            node.setIsRoot(form.getIsRoot());
            if(form.getDataSetId() != null){
                node.setDataSet(dataSetRepository.getReferenceById(form.getDataSetId()));
            }
            node.setMetadata(form.getMetadata());

        } catch (Exception e){
            log.error(e.getMessage(), e);
        }

    }




}
