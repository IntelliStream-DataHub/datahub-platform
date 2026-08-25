// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.dhconsole.services;

import ai.intellistream.datahub.api.responses.DataWrapper;
import ai.intellistream.datahub.api.responses.GraphDataWrapper;
import ai.intellistream.datahub.helpers.updates.UpdateListField;
import ai.intellistream.datahub.helpers.updates.UpdateMapField;
import ai.intellistream.datahub.helpers.updates.UpdateNumberField;
import ai.intellistream.datahub.helpers.updates.UpdateStringField;
import ai.intellistream.datahub.models.*;
import ai.intellistream.datahub.models.forms.RelFormWithId;
import ai.intellistream.datahub.resource.ResourceForm;
import ai.intellistream.datahub.resource.ResourceWebForm;
import ai.intellistream.dhconsole.api.DatahubApi;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;

@Service
public class ResourceApiService {

    private final DatahubApi datahubApi;

    public ResourceApiService(DatahubApi datahubApi) {
        this.datahubApi = datahubApi;
    }

    /**
     * Creates the resource and returns the API's whole response envelope.
     *
     * <p>The envelope rather than the node, because it may carry {@code warnings} — naming-policy
     * violations that were allowed through and recorded for review. Unwrapping to the node here
     * would drop them before the browser ever saw them, and a warning nobody is shown is the same
     * as no warning at all.
     */
    public GraphDataWrapper<ai.intellistream.datahub.models.NodeModel, EdgeProxy> save(ResourceWebForm resource){
        GraphDataWrapper<ResourceForm, RelForm> dataWrapper = new GraphDataWrapper<>();
        dataWrapper.getNodes().add(resource);

        if(resource.getRelationFrom() != null && !resource.getRelationTypes().isEmpty()){
            for(var relType : resource.getRelationTypes()){
                var relForm = new RelForm();
                relForm.setFromId(resource.getRelationFrom());
                relForm.setToExternalId(resource.getExternalId());
                relForm.setRelationshipType(relType);
                dataWrapper.getRelations().add(relForm);
            }
        }

        return this.datahubApi.createResourcesAndRelations(dataWrapper);
    }

    public EdgeProxy saveEdge(RelForm form){
        GraphDataWrapper<ResourceForm, RelForm> dataWrapper = new GraphDataWrapper<>();
        dataWrapper.getRelations().add(form);
        GraphDataWrapper<ai.intellistream.datahub.models.NodeModel, EdgeProxy> results = this.datahubApi.createResourcesAndRelations(dataWrapper);
        if( !results.getRelations().isEmpty() ){
            return results.getRelations().iterator().next();
        }
        return null;
    }

    public GraphDataWrapper<Resource, EdgeProxy> update(ResourceForm resource){
        GraphDataWrapper<UpdateResourceForm, UpdateRelForm> updateForm = new GraphDataWrapper<>();
        UpdateResourceForm updateResourceForm = new UpdateResourceForm( resource.getId() );
        updateForm.getNodes().add(updateResourceForm);

        updateResourceForm.getUpdate().setName(new UpdateStringField().set(resource.getName()));
        updateResourceForm.getUpdate().setExternalId(new UpdateStringField().set(resource.getExternalId()));
        if(resource.getDescription() != null){
            updateResourceForm.getUpdate().setDescription(new UpdateStringField().set(resource.getDescription()));
        }
        if(resource.getSource() != null){
            updateResourceForm.getUpdate().setSource(new UpdateStringField().set(resource.getSource()));
        }

        if(resource.getMetadata() != null){
            UpdateMapField metadataUpdate= new UpdateMapField();
            metadataUpdate.setSet(resource.getMetadata());
            updateResourceForm.getUpdate().setMetadata(metadataUpdate);
        }

        if(resource.getLabels() != null){
            List<String> labels = resource.getLabels();
            updateResourceForm.getUpdate().setLabels(new UpdateListField().set(labels));
        }

        // The edit form always renders the resource's current dataset, so what comes back is the
        // whole answer: an id moves the resource, an absent one clears it. Unlike the fields above
        // there is no "left alone" case to preserve — an empty picker is a decision, and treating
        // it as silence would make the field one-way (settable, never clearable).
        updateResourceForm.getUpdate().setDataSetId(resource.getDataSetId() == null
                ? new UpdateNumberField().setNull(true)
                : new UpdateNumberField().set(resource.getDataSetId()));

        // The envelope, not the node — see save().
        return this.datahubApi.updateResourcesAndRelations(updateForm);
    }

    public GraphDataWrapper<ai.intellistream.datahub.models.NodeModel, EdgeProxy> updateEdge(RelFormWithId form){
        GraphDataWrapper<UpdateResourceForm, UpdateRelForm> updateForm = new GraphDataWrapper<>();
        UpdateRelForm updateRelForm = new UpdateRelForm( form.getId() );
        updateForm.getRelations().add(updateRelForm);

        RelFields fields = new RelFields();
        updateRelForm.setUpdate(fields);

        if(form.getDescription() != null){
            fields.setDescription( new UpdateStringField().set(form.getDescription()) );
        }

        if(form.getRelationshipType() != null){
            fields.setRelationship( new UpdateStringField().set(form.getRelationshipType()) );
        }

        if(form.getRelationshipTypeId() != null){
            fields.setRelationshipId( new UpdateNumberField().set(form.getRelationshipTypeId()) );
        }

        if(form.getFromExternalId() != null){
            fields.setFromExternalId( new UpdateStringField().set(form.getFromExternalId()) );
        }
        if(form.getToExternalId() != null){
            fields.setToExternalId( new UpdateStringField().set(form.getToExternalId()) );
        }
        if(form.getFromId() != null){
            fields.setStart( new UpdateNumberField().set(form.getFromId()));
        }
        if(form.getToId() != null){
            fields.setEnd( new UpdateNumberField().set(form.getToId()));
        }

        if(form.getMetadata() != null){
            UpdateMapField metadataUpdate= new UpdateMapField();
            metadataUpdate.setSet(form.getMetadata());
            fields.setMetadata(metadataUpdate);
        }

        var responseData = datahubApi.updateResourcesAndRelations(updateForm);
        var edge = responseData.getRelations().stream().findFirst();
        // Repackage over NodeModel: the update echo is still flat Resources, but the byids
        // refresh below returns label-typed nodes.
        var enriched = new GraphDataWrapper<ai.intellistream.datahub.models.NodeModel, EdgeProxy>();
        enriched.setNodes(new java.util.ArrayList<>(responseData.getNodes()));
        enriched.setRelations(responseData.getRelations());
        if(edge.isPresent()){
            var edgeProxy = edge.get();
            var dataForm = new DataWrapper<IdCollection>();
            dataForm.getItems().add(IdCollection.createFromId(edgeProxy.getStart()));
            dataForm.getItems().add(IdCollection.createFromId(edgeProxy.getEnd()));
            var nodes = datahubApi.byIds(dataForm);
            enriched.setNodes(nodes.getItems());
        }

        return enriched;
    }

    public void deleteEdge(long id) {
        DataWrapper<IdCollection> dataForm = new DataWrapper<>();
        dataForm.getItems().add(IdCollection.createFromId(id));
        datahubApi.deleteEdges(dataForm);
    }
}
