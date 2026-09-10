// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.services;

import ai.intellistream.datahub.models.NodeModel;
import ai.intellistream.datahub.api.datasecurity.DataSecurity;
import ai.intellistream.datahub.api.responses.DataWrapper;
import ai.intellistream.datahub.api.responses.GraphDataWrapper;
import ai.intellistream.datahub.jpa.domains.EdgeEntity;
import ai.intellistream.datahub.jpa.domains.NodeEntity;
import ai.intellistream.datahub.jpa.domains.RelationshipType;
import ai.intellistream.datahub.models.EdgeProxy;
import ai.intellistream.datahub.models.IdCollection;
import ai.intellistream.datahub.models.RelForm;
import ai.intellistream.datahub.models.Resource;
import ai.intellistream.datahub.repositories.node.EdgeRepository;
import ai.intellistream.datahub.repositories.node.NodeRepository;
import ai.intellistream.datahub.repositories.node.RelationshipTypeRepository;
import ai.intellistream.datahub.resource.RelTypeForm;
import ai.intellistream.datahub.transformers.EdgeProxyTransformer;
import ai.intellistream.datahub.transformers.ResourceTransformer;
import lombok.AllArgsConstructor;
import org.apache.pulsar.client.api.PulsarClientException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

@Service
@AllArgsConstructor
public class EdgeService {

    private final EdgeRepository edgeRepository;

    private final NodeRepository nodeRepository;

    private final ResourceService resourceService;

    private final RelationshipTypeRepository relationshipTypeRepository;

    private final DataSecurity dataSecurity;

    @Transactional(readOnly = true)
    public DataWrapper<EdgeProxy> findById(Long id) {
        DataWrapper<EdgeProxy> data = new DataWrapper<>();
        EdgeEntity edge = edgeRepository.findById(id, EdgeEntity.class);
        if(edge != null && !readableOnly(List.of(edge), endpointsOf(List.of(edge))).isEmpty()){
            data.getItems().add(EdgeProxyTransformer.fromEdgeEntity(edge));
        }
        return data;
    }

    @Transactional(readOnly = true)
    public GraphDataWrapper<Resource, EdgeProxy> findByIdCollection(Collection<IdCollection> items) {
        GraphDataWrapper<Resource, EdgeProxy> data = new GraphDataWrapper<>();
        Collection<Long> idSet = items.stream().map(IdCollection::getId).collect(Collectors.toSet());
        List<EdgeEntity> edges = edgeRepository.findAllByIdIn(idSet, EdgeEntity.class);
        Map<Long, NodeEntity> endpoints = endpointsOf(edges);
        Collection<Long> nodeIdSet = new LinkedHashSet<>();
        for(var e : readableOnly(edges, endpoints)){
            data.getRelations().add(EdgeProxyTransformer.fromEdgeEntity(e));
            nodeIdSet.add(e.getStart());
            nodeIdSet.add(e.getEnd());
        }
        List<NodeEntity> nodes = nodeIdSet.stream().map(endpoints::get).toList();
        data.setNodes(ResourceTransformer.from(nodes));
        return data;
    }

    /**
     * An edge has no dataset of its own, so reading one is authorised on its endpoint nodes, the
     * same axis the write path uses ({@code ResourceService#assertCanWriteNodes}): the caller must
     * hold read on the datasets of <em>both</em> endpoints, because an edge necessarily reveals
     * both ends, and {@code /edges/byids} returns the endpoint nodes in full. Until this check
     * existed, any edge id in the tenant could be read regardless of dataset grants.
     *
     * <p>Denied edges are silently omitted, matching the "missing items are silently left out"
     * contract of the list endpoints; {@code findById} then reports 404, which keeps an unreadable
     * edge indistinguishable from a missing one (same reasoning as {@code ResourceService#get}).
     * An edge with a dangling or unresolvable endpoint fails closed: {@code endpointsById} has no
     * node for it and the orphan rule in {@link DataSecurity} denies the {@code null}.
     */
    private List<EdgeEntity> readableOnly(List<EdgeEntity> edges,
                                          Map<Long, NodeEntity> endpointsById) {
        return edges.stream()
                .filter(e -> canRead(endpointsById.get(e.getStart()))
                        && canRead(endpointsById.get(e.getEnd())))
                .toList();
    }

    private boolean canRead(NodeEntity endpoint) {
        return dataSecurity.hasReadPermissionToDataSet(endpoint);
    }

    private Map<Long, NodeEntity> endpointsOf(Collection<EdgeEntity> edges) {
        Set<Long> ids = new HashSet<>();
        for(EdgeEntity e : edges){
            if (e.getStart() != null) ids.add(e.getStart());
            if (e.getEnd() != null) ids.add(e.getEnd());
        }
        if (ids.isEmpty()) return Map.of();
        return nodeRepository.findAllByIdIn(ids, NodeEntity.class).stream()
                .collect(Collectors.toMap(NodeEntity::getId, n -> n));
    }

    /**
     * Create relationships between resources that already exist.
     *
     * <p>Delegates to {@link ResourceService#create} with a relations-only batch rather than
     * reimplementing edge creation, so this and {@code POST /resources/create} share one code path:
     * endpoint resolution by id or external id, find-or-create of the relationship type, the edge
     * rules (a link to a data set must be {@code BELONGS_TO}; a time series belongs to one data
     * set), the write check on <em>both</em> endpoints, and the Pulsar publish that keeps the Neo4j
     * graph in step. Creating nodes and edges atomically in one call is still
     * {@code /resources/create}; this is for linking resources that are already there.
     */
    @Transactional(rollbackFor = Exception.class)
    public DataWrapper<EdgeProxy> createRelationships(DataWrapper<RelForm> data) throws PulsarClientException {
        GraphDataWrapper<NodeModel, RelForm> request = new GraphDataWrapper<>();
        request.setRelations(data.getItems());
        GraphDataWrapper<NodeModel, EdgeProxy> created = resourceService.create(request);
        return new DataWrapper<EdgeProxy>().setItems(created.getRelations());
    }

    @Transactional
    public Collection<RelationshipType> createRelationshipTypes(DataWrapper<RelTypeForm> data){
        Collection<RelationshipType> instances = new ArrayList<>();
        for(RelTypeForm rt : data.getItems()){
            instances.add(saveRelationshipType(rt));
        }
        return instances;
    }

    @Transactional
    public RelationshipType saveRelationshipType(RelTypeForm form){
        RelationshipType rt = new RelationshipType();
        rt.setName(form.getName());
        rt.setDescription(form.getDescription());
        rt.setI18nCode(form.getI18nCode());
        relationshipTypeRepository.save(rt);
        return rt;
    }

    @Transactional
    public void deleteRelationships(DataWrapper<IdCollection> data) throws Exception {
        GraphDataWrapper<Resource, EdgeProxy> deleteRelations = new GraphDataWrapper<>();
        data.getItems().stream()
                .map(IdCollection::getId)
                .filter(Objects::nonNull)
                .forEach(id -> deleteRelations.getRelations().add(new EdgeProxy(id)));
        resourceService.delete(deleteRelations);
    }
}
