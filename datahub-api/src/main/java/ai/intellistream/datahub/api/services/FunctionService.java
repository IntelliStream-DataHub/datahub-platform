// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.services;

import ai.intellistream.datahub.models.NodeModel;
import ai.intellistream.datahub.api.datasecurity.DataSecurity;
import ai.intellistream.datahub.api.responses.DataWrapper;
import ai.intellistream.datahub.api.responses.GraphDataWrapper;
import ai.intellistream.datahub.jpa.domains.FunctionEntity;
import ai.intellistream.datahub.function.Function;
import ai.intellistream.datahub.models.EdgeProxy;
import ai.intellistream.datahub.models.IdCollection;
import ai.intellistream.datahub.models.RelForm;
import ai.intellistream.datahub.models.Resource;
import ai.intellistream.datahub.models.UpdateRelForm;
import ai.intellistream.datahub.models.UpdateResourceForm;
import ai.intellistream.datahub.repositories.node.FunctionRepository;
import ai.intellistream.datahub.transformers.FunctionTransformer;
import lombok.extern.slf4j.Slf4j;
import org.apache.pulsar.client.api.PulsarClientException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * A Function is a plain datastore node distinguished only by its {@code FUNCTION}
 * type-label. This service is a thin adapter over the shared {@link ResourceService}
 * pipeline — every write goes through the same dataset-ACL enforcement, optimistic
 * locking, graph-connectivity validation, and Neo4j CUD publishing that resources get.
 * The {@code FUNCTION} label (always present on {@link Function}) is what makes
 * {@code NodeService.createFromResource} build a {@code FunctionEntity} rather than a
 * plain resource.
 */
@Service
@Slf4j
public class FunctionService {

    private final FunctionRepository functionRepository;
    private final ResourceService resourceService;
    private final DataSecurity dataSecurity;

    public FunctionService(FunctionRepository functionRepository,
                           ResourceService resourceService,
                           DataSecurity dataSecurity) {
        this.functionRepository = functionRepository;
        this.resourceService = resourceService;
        this.dataSecurity = dataSecurity;
    }

    /**
     * Create one or more functions through the shared resource pipeline. The response is
     * re-read from Postgres as {@link Function} DTOs so callers keep the function-shaped
     * response.
     */
    @Transactional
    public DataWrapper<Function> create(DataWrapper<Function> apiReqData) throws PulsarClientException {
        var graph = new GraphDataWrapper<NodeModel, RelForm>();
        // Straight through: the pipeline takes NodeModel and Function is one, so its FUNCTION
        // type-label drives the dispatch with nothing copied by hand — a field added to Function
        // later cannot be silently dropped on the way in.
        graph.setNodes(new ArrayList<>(apiReqData.getItems()));
        graph.setRelations(new ArrayList<>());

        GraphDataWrapper<NodeModel, EdgeProxy> created = resourceService.create(graph);

        List<Long> ids = created.getNodes().stream()
                .map(NodeModel::getId)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());

        var result = new DataWrapper<Function>();
        result.setItems(new ArrayList<>(FunctionTransformer.toFunction(functionRepository.findAllById(ids))));
        // Same reason as the data set adapter: the shared path judged the name, and re-wrapping
        // the response would otherwise swallow what it found.
        result.setWarnings(created.getWarnings());
        return result;
    }

    /**
     * List all functions for the current tenant. No filtering for v1 — function inventory
     * is expected to be small.
     */
    @Transactional(readOnly = true)
    public DataWrapper<Function> list() {
        List<FunctionEntity> entities = functionRepository.findAll();

        // Narrow to what the caller may read, like every other node read. Create/update/delete get
        // their dataset ACL from the shared ResourceService pipeline, but list() queries the
        // repository directly, so it was returning every function on the tenant regardless of
        // grants. Functions with no dataset stay visible to everyone, matching how an orphan node
        // is treated elsewhere.
        if (!dataSecurity.hasReadAccessToEverything()) {
            Set<Long> readable = dataSecurity.readableDataSetIds();
            entities = entities.stream()
                    .filter(e -> e.getDataSet() == null || readable.contains(e.getDataSet().getId()))
                    .toList();
        }

        var results = new DataWrapper<Function>();
        results.setItems(new ArrayList<>(FunctionTransformer.toFunction(entities)));
        return results;
    }

    /**
     * Update functions (and any relations) through the shared resource pipeline. Functions
     * are editable like resources; the intrinsic {@code FUNCTION} type-label stays immutable
     * via the label-resolution rules in {@link ResourceService}.
     */
    @Transactional
    public GraphDataWrapper<NodeModel, EdgeProxy> update(
            GraphDataWrapper<UpdateResourceForm, UpdateRelForm> apiReqData) throws PulsarClientException {
        return resourceService.update(apiReqData);
    }

    /**
     * Delete one or more functions by id or externalId through the shared resource pipeline
     * (dataset-ACL, subscription, and graph-connectivity checks all apply).
     */
    @Transactional
    public void delete(DataWrapper<IdCollection> apiReqData) throws PulsarClientException {
        if (apiReqData.getItems() == null || apiReqData.getItems().isEmpty()) return;

        var graph = new GraphDataWrapper<Resource, EdgeProxy>();
        apiReqData.getItems().forEach(it -> {
            Resource r = new Resource();
            if (it.getId() != null) {
                r.setId(it.getId());
                graph.getNodes().add(r);
            } else if (it.getExternalId() != null) {
                r.setExternalId(it.getExternalId());
                graph.getNodes().add(r);
            }
        });
        resourceService.delete(graph);
    }
}
