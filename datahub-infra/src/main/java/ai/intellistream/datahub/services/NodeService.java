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
import java.util.Set;
import ai.intellistream.datahub.models.NodeModelSubtypes;
import ai.intellistream.datahub.models.GeoLocation;
import ai.intellistream.datahub.models.Asset;
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

    /**
     * Build the concrete entity a create body asks for. The body's runtime DTO type and its
     * type-label are two spellings of the same fact — over HTTP the label-keyed deserializer
     * already made them agree; for direct callers a mismatch is rejected here. A body typed as
     * plain {@link Resource} dispatches on its type-label alone (the adapter services build
     * exactly that shape), and no type-label at all is a plain resource.
     */
    @Transactional
    public NodeEntity createFromResource(NodeModel form) throws InvalidResourceException {
        try {
            List<Label> labels = labelService.findAllAndCreateFromNames(form.getLabels());
            List<String> labelNames = labels.stream().map(Label::getName).toList();

            // A node's type is intrinsic: it may carry at most one type-label. Reject ambiguous input
            // rather than silently letting a dispatch order pick a winner.
            long typeLabelCount = labelNames.stream().filter(TypeLabels::isTypeLabel).distinct().count();
            if (typeLabelCount > 1) {
                throw invalidResource("A node may have at most one type-label (one of " + TypeLabels.ALL + ").");
            }

            Set<String> types = TypeLabels.typeLabelsIn(labelNames);
            String labelType = types.isEmpty() ? null : types.iterator().next();
            String dtoType = dtoTypeOf(form);
            if (dtoType != null && labelType != null && !dtoType.equals(labelType)) {
                throw invalidResource("The body's type (" + dtoType + ") and its type-label ("
                        + labelType + ") disagree.");
            }
            String type = dtoType != null ? dtoType : labelType;

            // TypeLabels.CREATABLE is the single authority for which types this API may mint.
            if (type != null && !TypeLabels.CREATABLE.contains(type)) {
                throw invalidResource("Not allowed to create " + type + " using the resource api!");
            }

            if (TypeLabels.TIMESERIES.equals(type)) {
                if (!(form instanceof Timeseries ts)) {
                    // Unreachable over HTTP (the deserializer binds TIMESERIES-labelled bodies as
                    // Timeseries); guards a direct caller handing a flat shape that cannot carry
                    // the type-specific fields.
                    throw invalidResource("A TIMESERIES create must use the Timeseries shape.");
                }
                return mapNewNodeFromTimeseries(ts);
            }

            NodeEntity node;
            if (TypeLabels.ASSET.equals(type)) {
                AssetEntity asset = new AssetEntity();
                GeoLocation geo = form instanceof Asset a ? a.getGeoLocation()
                        : form instanceof Resource r ? r.getGeoLocation() : null;
                asset.setGeoLocation(geo == null ? null : geo.getJson());
                node = asset;
            } else if (TypeLabels.FUNCTION.equals(type)) {
                node = new FunctionEntity();
            } else if (TypeLabels.DATASET.equals(type)) {
                node = new DatasetEntity();
            } else if (TypeLabels.POLICY.equals(type)) {
                node = new PolicyEntity();
            } else {
                node = new ResourceEntity();
            }
            applyLabels(node, labels);
            mapCommonNodeFields(node, form);
            // isRoot is not a NodeModel primitive; it exists on the Resource/Asset shapes only.
            if (form instanceof Asset a) {
                node.setIsRoot(a.getIsRoot());
            } else if (form instanceof Resource r) {
                node.setIsRoot(r.getIsRoot());
            }
            return node;
        } catch(InvalidResourceException e) {
            throw e;
        } catch (Exception e){
            log.error(e.getMessage(), e);
            throw e;
        }
    }

    /** The type-label this DTO's runtime class stands for; null for the base/plain-resource shapes. */
    private static String dtoTypeOf(NodeModel form) {
        for (var entry : NodeModelSubtypes.BY_TYPE_LABEL.entrySet()) {
            if (entry.getValue().isInstance(form)) {
                return entry.getKey();
            }
        }
        return null;
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
