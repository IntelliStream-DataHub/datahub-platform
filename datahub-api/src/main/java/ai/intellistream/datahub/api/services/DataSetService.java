// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.services;

import ai.intellistream.datahub.helpers.text.ExternalIds;
import ai.intellistream.datahub.api.controllers.errors.*;
import ai.intellistream.datahub.models.paging.PageCursor;
import ai.intellistream.datahub.repositories.node.NodeSort;
import ai.intellistream.datahub.api.responses.DataWrapper;
import ai.intellistream.datahub.api.responses.GraphDataWrapper;
import ai.intellistream.datahub.errors.ObjectNotFoundException;
import ai.intellistream.datahub.errors.ResponseError;
import ai.intellistream.datahub.helpers.updates.UpdateStringField;
import ai.intellistream.datahub.helpers.utils.IdGenerator;
import ai.intellistream.datahub.jpa.domains.DatasetEntity;
import ai.intellistream.datahub.jpa.domains.NodeEntity;
import ai.intellistream.datahub.jpa.dto.NameAndEId;
import ai.intellistream.datahub.models.DataSetModel;
import ai.intellistream.datahub.models.DataSetRetreiver;
import ai.intellistream.datahub.models.datafilters.DataSetFilter;
import ai.intellistream.datahub.models.UpdateRelForm;
import ai.intellistream.datahub.models.UpdateResourceForm;
import ai.intellistream.datahub.models.forms.DataSetForm;
import ai.intellistream.datahub.repositories.node.DataSetRepository;
import ai.intellistream.datahub.repositories.node.NodeRepository;
import ai.intellistream.datahub.transformers.DataSetTransformer;
import ai.intellistream.datahub.transformers.ResourceTransformer;
import ai.intellistream.datahub.models.SearchBody;
import org.apache.pulsar.client.api.PulsarClientException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class DataSetService {

    private final NodeRepository nodeRepository;
    private final ResourceService resourceService;
    private final DataSetRepository dataSetRepository;

    public DataSetService(
            NodeRepository nodeRepository,
            ResourceService resourceService, DataSetRepository dataSetRepository) {
        this.nodeRepository = nodeRepository;
        this.resourceService = resourceService;
        this.dataSetRepository = dataSetRepository;
    }

    @Transactional
    public DataWrapper<DataSetModel> update(DataWrapper<DataSetForm> apiReqData) throws PulsarClientException {
        Collection<DataSetForm> items = apiReqData.getItems();
        // Create error list that can return missing datasets to user
        ResponseError<BadRequestError> errors = new ResponseError<>();
        List<DataSetModel> dataSets = new ArrayList<>();

        // Validate if new external ids already exists
        validateUniqueExternalId(items);

        for(DataSetForm updateData : items ){


            Long id = updateData.getId();
            String externalId = updateData.getExternalId();
            DatasetEntity dataSet  = dataSetRepository.findByIdOrExternalId(id,externalId).orElseThrow(()-> {
                var de = new BadRequestError();
                de.setMessage("DataSet cannot be found.");
                de.getFields().add(Map.of("externalId", String.valueOf(externalId), "id", String.valueOf(id)));
                errors.setError(de);
                return new BadRequestException(errors);
            });


            // Map resource form into NodeEntity object
            dataSets.add( DataSetTransformer.toDataSetModel( ResourceTransformer.from( validateAndUpdate(dataSet, updateData) )));

        }

        DataWrapper<DataSetModel> out = new DataWrapper<>();
        out.setItems(dataSets);
        return out;
    }

    @Transactional
    public DatasetEntity validateAndUpdate(DatasetEntity dataSet, DataSetForm form) throws PulsarClientException {
        ResponseError<BadRequestError> errors = new ResponseError<>();
        if(form.getUpdate() != null && !form.getUpdate().validateUpdateFields()){
            errors.setError(new BadRequestError());
            form.getUpdate().getErrors().forEach( error -> {
                errors.getError().addFieldError(error.getObjectName(), error.getDefaultMessage());
            });
            throw new BadRequestException(errors);
        }
        GraphDataWrapper<UpdateResourceForm, UpdateRelForm> updateGraphData = new GraphDataWrapper<>();
        var urf = new UpdateResourceForm(form.getId());
        urf.setExternalId(form.getExternalId());

        if(form.getUpdate() != null){
            if(form.getUpdate().getName().getSet() != null){
                urf.getUpdate().setName(new UpdateStringField().set(form.getUpdate().getName().getSet()));
            }
            if(form.getUpdate().getDescription().getSet() != null){
                urf.getUpdate().setDescription(new UpdateStringField().set(form.getUpdate().getDescription().getSet()));
            }
            // Pass setNull through so a dataset description can be cleared — the resource layer
            // honors it. Without this the documented `setNull` support was silently a no-op.
            if(form.getUpdate().getDescription().getSetNull()){
                urf.getUpdate().setDescription(new UpdateStringField().setNull(true));
            }
            if(form.getUpdate().getExternalId().getSet() != null){
                urf.getUpdate().setExternalId(new UpdateStringField().set(form.getUpdate().getExternalId().getSet()));
            }
            if(form.getUpdate().getMetadata().getSet() != null){
                urf.getUpdate().getMetadata().setSet(form.getUpdate().getMetadata().getSet());
            }
            if(form.getUpdate().getMetadata().getAdd() != null){
                urf.getUpdate().getMetadata().add(form.getUpdate().getMetadata().getAdd());
            }
            if(form.getUpdate().getMetadata().getRemove() != null){
                urf.getUpdate().getMetadata().remove(form.getUpdate().getMetadata().getRemove());
            }

            // Label edits pass straight through to ResourceService.update, which enforces the
            // immutable DATASET type-label — so we don't special-case it here.
            if(form.getUpdate().getLabels().getSet() != null){
                urf.getUpdate().getLabels().set(form.getUpdate().getLabels().getSet());
            }
            if(form.getUpdate().getLabels().getAdd() != null){
                urf.getUpdate().getLabels().add(form.getUpdate().getLabels().getAdd());
            }
            if(form.getUpdate().getLabels().getRemove() != null){
                urf.getUpdate().getLabels().remove(form.getUpdate().getLabels().getRemove());
            }
        }

        updateGraphData.getNodes().add(urf);
        this.resourceService.update(updateGraphData);

        return dataSet;
    }

    private void validateUniqueExternalId(Collection<DataSetForm> items) throws DuplicateDataException{
        Set<Long> externalIdHashCollection = items.stream().map( it -> {
            // An item may carry no update block (getUpdate() is null then) — skip it rather than NPE.
            if(it.getUpdate() != null && it.getUpdate().getExternalId().getSet() != null){
                long id = ExternalIds.hash( it.getUpdate().getExternalId().getSet() );
                return id;
            }
            return null;
        }).filter(Objects::nonNull).collect(Collectors.toSet());

        // Validate if externalId already exists.
        List<NameAndEId> existingEntries = nodeRepository.findAllByExternalIdHashIn(externalIdHashCollection.stream().toList(), NameAndEId.class);
        if(!existingEntries.isEmpty()){
            List<Map<String, String>> existingExternalIds = existingEntries.stream()
                    .map( it -> Map.of("externalId", it.getExternalId()))
                    .toList();
            ResponseError<DuplicateError> responseError = new ResponseError<>();
            var duplicateError = new DuplicateError();
            duplicateError.setMessage("DataSet with external id already exists.");
            duplicateError.setDuplicated(existingExternalIds);
            responseError.setError(duplicateError);
            throw new DuplicateDataException(responseError);
        }
    }

    /**
     * Structured AND-combined dataset filter, the dataset counterpart to
     * {@code ResourceService.filter} and {@code TimeseriesService.filter}: external-id prefix,
     * metadata entries, and created/last-updated ranges. Results come newest first, capped by the
     * retriever's limit.
     *
     * <p>Unlike the resource and timeseries filters this applies no dataset-ACL narrowing, because
     * the other dataset reads ({@code /datasets/list}, {@code /byids}, {@code /search}) do not
     * either — a dataset is the unit access is granted *on*, and its own row has no dataSetId to
     * narrow by. Filtering therefore exposes nothing that {@code /datasets/list} did not already.
     */
    /**
     * Fetch a single dataset by its numeric id.
     *
     * <p>Missing ids throw {@link ObjectNotFoundException} → 404 via the shared advice, matching
     * every other single-item GET. No ACL narrowing, for the reason given on {@link #filter}: a
     * dataset is the unit access is granted on, so its own row is not scoped by a dataset grant —
     * this returns nothing {@code /datasets/list} did not already.
     */
    @Transactional(readOnly = true)
    public DataWrapper<DataSetModel> get(Long id) {
        DatasetEntity entity = dataSetRepository.findById(id).orElseThrow(() ->
                new ObjectNotFoundException("Data set with id: " + id + " Not found!"));

        var data = new DataWrapper<DataSetModel>();
        data.getItems().add(DataSetTransformer.toDataSetModel(ResourceTransformer.from(entity)));
        return data;
    }

    @Transactional(readOnly = true)
    public DataWrapper<DataSetModel> filter(DataSetRetreiver form) {
        NodeSort sort = NodeSort.resolve(form.getSort());
        PageCursor cursor = NodePaging.validated(form.getCursor(), sort);

        List<DatasetEntity> results = dataSetRepository.filter(form.getFilter(), form.getLimit(), sort, cursor);
        var data = new DataWrapper<DataSetModel>();
        data.setItems(DataSetTransformer.toDataSetModel(ResourceTransformer.from(results)));
        data.setNextCursor(NodePaging.nextCursor(results, form.getLimit(), sort));
        return data;
    }

    /**
     * Full-text search over datasets, optionally narrowed by a {@link DataSetFilter}.
     *
     * <p>One query: the phrase is a predicate beside the filter's, not a separate pass. Search is
     * {@link #filter} plus a phrase, and reads as that here. The filter used to be accepted and
     * dropped on the floor, and the phrase used to run as its own native query up to a candidate
     * ceiling, which made a filtered search over a common phrase quietly incomplete.
     *
     * <p>No ACL narrowing, for the reason given on {@link #filter}: a dataset is the unit access is
     * granted on, so its own row is not scoped by a dataset grant.
     *
     * <p>No match is a normal empty result, not a 404 — this endpoint used to throw, which sent the
     * console search box a plain-string 404 body it could not parse, so it silently showed nothing.
     */
    @Transactional(readOnly = true)
    public DataWrapper<DataSetModel> search(SearchBody<DataSetFilter> form) {
        List<DatasetEntity> results = dataSetRepository.search(
                form.getSearch().getQuery(), form.getFilter(), form.getLimit(), NodeSort.DEFAULT, null);

        var data = new DataWrapper<DataSetModel>();
        data.setItems(DataSetTransformer.toDataSetModel(ResourceTransformer.from(results)));
        return data;
    }
}
