// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.services;

import ai.intellistream.datahub.errors.FieldError;
import ai.intellistream.datahub.errors.InvalidResourceException;
import ai.intellistream.datahub.errors.ResponseError;
import ai.intellistream.datahub.jpa.domains.*;
import ai.intellistream.datahub.models.Resource;
import ai.intellistream.datahub.repositories.node.DataSetRepository;

import ai.intellistream.datahub.function.Function;
import ai.intellistream.datahub.models.DataSetModel;
import ai.intellistream.datahub.models.Policy;
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
            // Only an asset or a plain resource can be a navigation root. A root request has two
            // spellings — the typed DTOs have no isRoot at all, so the base captures it, while the
            // legacy flat body sets Resource's own field whatever label it carries — and both must
            // be judged. `false` means nothing (the flat shape sends it on every body) and passes;
            // `true` on a type that cannot be a root is refused rather than quietly dropped.
            Boolean requestedIsRoot = form instanceof Asset asset ? asset.getIsRoot()
                    : form instanceof Resource res ? res.getIsRoot()
                    : form.getUnsupportedIsRoot();
            boolean canBeRoot = type == null || TypeLabels.ASSET.equals(type);
            if (!canBeRoot && Boolean.TRUE.equals(requestedIsRoot)) {
                throw invalidResource("A " + type + " cannot be a navigation root; remove isRoot.");
            }
            // Say so rather than dropping it. A data set or policy node is an orphan by
            // construction — that is what the ACL's write-everything rule keys on, and populating
            // the column would leave a managed node mutable under a per-dataset grant
            // (POLICY_DATASETID_BUG.md). The caller has already been authorized against the id
            // they sent, so answering 201 while ignoring it would report work never done.
            if ((TypeLabels.DATASET.equals(type) || TypeLabels.POLICY.equals(type))
                    && form.getDataSetId() != null) {
                throw invalidResource("A " + type + " node cannot belong to a data set; "
                        + "remove dataSetId. Hierarchy is expressed with BELONGS_TO edges.");
            }

            if (TypeLabels.TIMESERIES.equals(type)) {
                if (!(form instanceof Timeseries ts)) {
                    // Unreachable over HTTP (the deserializer binds TIMESERIES-labelled bodies as
                    // Timeseries); guards a direct caller handing a flat shape that cannot carry
                    // the type-specific fields.
                    throw invalidResource("A TIMESERIES create must use the Timeseries shape.");
                }
                // relatedResources is how the /timeseries endpoint asks for PUBLISH_DATA_TO
                // edges, and that edge-building lives in TimeseriesService, which this path does
                // not go through. Silently creating the series with no publisher edges would be
                // data loss the caller never hears about, so say so: the same request can express
                // it as a relation in relations[], which this path does handle.
                if (!ts.getRelatedResources().isEmpty()) {
                    throw invalidResource("A time series created through the resource api cannot "
                            + "carry relatedResources; express the link in relations[] instead, or "
                            + "create it through /timeseries.");
                }
                return mapNewNodeFromTimeseries(ts, labels);
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
            // isRoot is not a NodeModel primitive; it is applied only where it is legal, and the
            // guard above has already refused a true anywhere else. The entity defaults to false.
            if (canBeRoot && requestedIsRoot != null) {
                node.setIsRoot(requestedIsRoot);
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
        // A switch on the runtime type, not a scan of the registry. BY_TYPE_LABEL is a Map.of,
        // whose iteration order is randomised per JVM run, so "first entry that isInstance"
        // silently becomes non-deterministic the moment one node DTO extends another — the same
        // body would build different entity types on different boots. Ordering by specificity is
        // the compiler's job here.
        return switch (form) {
            case Asset _ -> TypeLabels.ASSET;
            case Timeseries _ -> TypeLabels.TIMESERIES;
            case DataSetModel _ -> TypeLabels.DATASET;
            case Policy _ -> TypeLabels.POLICY;
            case Function _ -> TypeLabels.FUNCTION;
            default -> null;   // Resource, and the base shape the adapters build
        };
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
        return mapNewNodeFromTimeseries(ts, labelService.findAllAndCreateFromNames(new ArrayList<>(ts.getLabels())));
    }

    /**
     * With the labels already resolved. The resource path has them in hand by the time it
     * dispatches here, and resolving is a find-or-create round trip per name — repeating it cost a
     * second one for every item in a create batch.
     */
    @Transactional
    public TimeseriesEntity mapNewNodeFromTimeseries(Timeseries ts, List<Label> labels){
        TimeseriesEntity node = new TimeseriesEntity();
        // Resolve whatever labels the caller sent, not just the type-label. This used to build a
        // single hardcoded TIMESERIES LabelForm, so a create that carried extra domain labels
        // had them silently dropped — the one node type that could not be labelled on create.
        // Timeseries.setLabels (via NodeModel) already guarantees TIMESERIES is in the list, so
        // the type-label cannot be lost by going through the caller's set.
        applyLabels(node, labels);

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
