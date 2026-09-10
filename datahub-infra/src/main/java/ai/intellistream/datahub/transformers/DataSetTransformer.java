// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.transformers;

import ai.intellistream.datahub.api.responses.GraphDataWrapper;

import ai.intellistream.datahub.jpa.domains.DatasetEntity;
import ai.intellistream.datahub.jpa.domains.PolicyEntity;
import ai.intellistream.datahub.jpa.domains.TypeLabels;
import ai.intellistream.datahub.models.*;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public class DataSetTransformer {


    public static Collection<DataSetModel> toResources(Collection<DataSetModel> items){
        return items.stream().map(DataSetTransformer::toResource).toList();
    }

    /**
     * The create command for a data set.
     *
     * <p>Builds a {@link DataSetModel}, not a {@code Resource} wearing a DATASET label. Over HTTP
     * the label-keyed deserializer types a body before the create path sees it, so the DTO class
     * and the type-label always agree and {@code NodeService} can cross-check them. An in-process
     * adapter has no deserializer, so whatever it constructs <em>is</em> the type — and while this
     * built a plain {@code Resource}, that cross-check passed vacuously and dispatch rested
     * entirely on the label being right. Building the real type makes both mechanisms say the same
     * thing, and {@code DataSetModel} seeds its own DATASET label, so it cannot be forgotten.
     */
    public static DataSetModel toResource(DataSetModel item){
        DataSetModel r = new DataSetModel();
        r.setId(item.getId());
        r.setExternalId(item.getExternalId());
        r.setDescription(item.getDescription());
        r.setName(item.getName());
        r.setSource(item.getSource());
        r.setMetadata(item.getMetadata());
        // Carry the caller's labels through; this used to replace them with List.of("DATASET"), so
        // create was the one dataset path that could not label anything. setLabels keeps the
        // DATASET type-label present however the list arrives.
        r.setLabels(new ArrayList<>(item.getLabels()));
        return r;
    }

    /**
     * {@code DatasetEntity} to {@link DataSetModel} — the read path's entry point.
     *
     * <p>{@code policies} and {@code connectedDataSets} stay empty: they are how a create request
     * asks for edges, not state stored on the row, so a read has nothing to put in them. The
     * hierarchy a client wants is in {@code relatedResources}, from the graph.
     */
    public static DataSetModel from(DatasetEntity entity) {
        return NodeBaseFields.apply(new DataSetModel(), entity);
    }

    public static Collection<DataSetModel> toDataSetModel(Collection<? extends NodeModel> results) {
        return results.stream().map(DataSetTransformer::toDataSetModel).toList();
    }

    public static DataSetModel toDataSetModel(NodeModel item) {
        DataSetModel dataSet = new DataSetModel();
        dataSet.setId( item.getId() );
        dataSet.setExternalId( item.getExternalId() );
        dataSet.setDescription( item.getDescription() );
        dataSet.setName( item.getName() );
        dataSet.setSource( item.getSource() );
        dataSet.setCreatedTime( item.getCreatedTime() );
        dataSet.setLastUpdatedTime( item.getLastUpdatedTime() );
        dataSet.setLabels( item.getLabels() );
        item.getMetadata().forEach( (key, value) -> dataSet.getMetadata().put(key, value));
        return dataSet;
    }

    public static GraphDataWrapper<NodeModel, RelForm> toGraphForm(
            Collection<DataSetModel> dataSets,
            List<PolicyEntity> existingPolicies,
            List<IdCollection> connectedDataSets
    ) {
        GraphDataWrapper<NodeModel, RelForm> graphForm = new GraphDataWrapper<>();
        dataSets.forEach(dataSetModel -> {
            DataSetModel resource = toResource(dataSetModel);
            graphForm.getNodes().add(resource);

            // Attach data set to other data sets
            for(IdCollection connectedDs : connectedDataSets){
                for(long id : dataSetModel.getConnectedDataSets()){
                    if(connectedDs.getId() == id){
                        RelForm relForm = new RelForm();
                        relForm.setFromExternalId(connectedDs.getExternalId());
                        relForm.setToExternalId(dataSetModel.getExternalId());
                        relForm.setRelationshipType("BELONGS_TO");
                        graphForm.getRelations().add(relForm);
                        break;
                    }
                }
            }

            for(String policyExternalId : dataSetModel.getPolicies()){

                attachPolicy(graphForm, dataSetModel, policyExternalId, existingPolicies);
            }
        });
        return graphForm;
    }

    private static void attachPolicy(
            GraphDataWrapper<NodeModel, RelForm> graphForm,
            DataSetModel dataSetModel,
            String policyExternalId,
            List<PolicyEntity> existingPolicies
    ) {
        Optional<PolicyEntity> existingPolicy = existingPolicies.stream()
                .filter(it -> it.getExternalId().equals(policyExternalId) )
                .findFirst();
        if(existingPolicy.isPresent()){
            var relForm = new RelForm();
            relForm.setFromExternalId (dataSetModel.getExternalId());
            relForm.setToExternalId(existingPolicy.get().getExternalId());
            relForm.setRelationshipType("ENFORCED_ON");
            graphForm.getRelations().add(relForm);
        }
    }
}
