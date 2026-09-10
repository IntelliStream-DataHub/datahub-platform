// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.services;

import ai.intellistream.datahub.api.datasecurity.DataSecurity;
import ai.intellistream.datahub.api.datasecurity.DatasetPermissions;
import ai.intellistream.datahub.api.datasecurity.TestDataSecurity;
import ai.intellistream.datahub.api.responses.DataWrapper;
import ai.intellistream.datahub.api.responses.GraphDataWrapper;
import ai.intellistream.datahub.jpa.domains.DatasetEntity;
import ai.intellistream.datahub.jpa.domains.EdgeEntity;
import ai.intellistream.datahub.jpa.domains.NodeEntity;
import ai.intellistream.datahub.jpa.domains.RelationshipType;
import ai.intellistream.datahub.models.EdgeProxy;
import ai.intellistream.datahub.models.IdCollection;
import ai.intellistream.datahub.models.Resource;
import ai.intellistream.datahub.repositories.node.EdgeRepository;
import ai.intellistream.datahub.repositories.node.NodeRepository;
import ai.intellistream.datahub.repositories.node.RelationshipTypeRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Dataset-ACL coverage for edge <em>reads</em> in {@link EdgeService}.
 *
 * <p>The write side (create / re-point / delete) has always been authorised on both endpoint
 * nodes, see {@link ResourceServiceEdgeAccessTest}. The read side was not: until the check these
 * tests lock in, {@code GET /edges/{id}} and {@code POST /edges/byids} returned any edge in the
 * tenant regardless of dataset grants, and {@code /byids} handed back the endpoint nodes in full.
 * Reading an edge now requires read on the datasets of <em>both</em> endpoints, because the edge
 * necessarily reveals both ends.
 *
 * <p>Denials are indistinguishable from absence: {@code findById} returns an empty wrapper (the
 * controller reports 404) and {@code findByIdCollection} silently omits the edge, matching the
 * "missing items are silently left out" contract of the list endpoints.
 */
class EdgeServiceReadAccessTest {

    private final EdgeRepository edgeRepository = mock(EdgeRepository.class);
    private final NodeRepository nodeRepository = mock(NodeRepository.class);
    private final ResourceService resourceService = mock(ResourceService.class);
    private final RelationshipTypeRepository relationshipTypeRepository =
            mock(RelationshipTypeRepository.class);
    // Grants stated as dataset ids; see TestDataSecurity for why.
    private DatasetPermissions permissions = DatasetPermissions.none();
    private final DataSecurity dataSecurity = TestDataSecurity.backedBy(() -> permissions);

    private final EdgeService service = new EdgeService(
            edgeRepository, nodeRepository, resourceService, relationshipTypeRepository,
            dataSecurity);

    /** The caller may read exactly these datasets, and write nothing. */
    private void canRead(Long... datasetIds) {
        permissions = DatasetPermissions.of(false, false, Set.of(datasetIds), Set.of());
    }

    /** A caller holding the all-datasets read grant. */
    private void canReadEverything() {
        permissions = DatasetPermissions.of(true, false, Set.of(), Set.of());
    }

    /** A node that lives in {@code datasetId}, as returned by the endpoint lookup. */
    private static NodeEntity nodeInDataset(long nodeId, long datasetId) {
        DatasetEntity ds = mock(DatasetEntity.class);
        when(ds.getId()).thenReturn(datasetId);
        NodeEntity node = mock(NodeEntity.class);
        when(node.getId()).thenReturn(nodeId);
        when(node.getDataSet()).thenReturn(ds);
        return node;
    }

    private static EdgeEntity edge(long id, long start, long end) {
        RelationshipType type = new RelationshipType();
        type.setName("FLOWS_TO");
        EdgeEntity edge = new EdgeEntity();
        edge.setId(id);
        edge.setStart(start);
        edge.setEnd(end);
        edge.setRelationshipType(type);
        return edge;
    }

    /** Stub the endpoint lookup that {@code endpointsOf} performs. */
    private void endpointsAre(NodeEntity... nodes) {
        when(nodeRepository.findAllByIdIn(anyCollection(), eq(NodeEntity.class)))
                .thenReturn(List.of(nodes));
    }

    // ---- findById ----------------------------------------------------------------------------

    @Test
    void findById_omitsTheEdgeWhenNeitherEndpointIsReadable() {
        canRead(7L);
        when(edgeRepository.findById(50L, EdgeEntity.class)).thenReturn(edge(50L, 1L, 2L));
        // Both endpoints sit in dataset 9, which the caller cannot read.
        endpointsAre(nodeInDataset(1L, 9L), nodeInDataset(2L, 9L));

        DataWrapper<EdgeProxy> result = service.findById(50L);

        assertThat(result.getItems()).isEmpty();
    }

    /**
     * The leak-shaped case: read on one endpoint must not reveal the edge, because the edge
     * discloses the far endpoint's id and its relationship to the readable node.
     */
    @Test
    void findById_omitsTheEdgeWhenOnlyOneEndpointIsReadable() {
        canRead(7L);
        when(edgeRepository.findById(50L, EdgeEntity.class)).thenReturn(edge(50L, 1L, 2L));
        endpointsAre(nodeInDataset(1L, 7L), nodeInDataset(2L, 9L));

        assertThat(service.findById(50L).getItems()).isEmpty();
    }

    @Test
    void findById_returnsTheEdgeWhenBothEndpointsAreReadable() {
        canRead(7L, 9L);
        when(edgeRepository.findById(50L, EdgeEntity.class)).thenReturn(edge(50L, 1L, 2L));
        endpointsAre(nodeInDataset(1L, 7L), nodeInDataset(2L, 9L));

        DataWrapper<EdgeProxy> result = service.findById(50L);

        assertThat(result.getItems()).hasSize(1);
        assertThat(result.getItems().iterator().next().getId()).isEqualTo(50L);
    }

    @Test
    void findById_readAllCallerSeesAnyEdge() {
        canReadEverything();
        when(edgeRepository.findById(50L, EdgeEntity.class)).thenReturn(edge(50L, 1L, 2L));
        endpointsAre(nodeInDataset(1L, 9L), nodeInDataset(2L, 11L));

        assertThat(service.findById(50L).getItems()).hasSize(1);
    }

    /** A dangling endpoint cannot be authorised, so the edge fails closed. */
    @Test
    void findById_omitsTheEdgeWhenAnEndpointDoesNotResolve() {
        canRead(7L);
        when(edgeRepository.findById(50L, EdgeEntity.class)).thenReturn(edge(50L, 1L, 404L));
        endpointsAre(nodeInDataset(1L, 7L));

        assertThat(service.findById(50L).getItems()).isEmpty();
    }

    // ---- findByIdCollection ------------------------------------------------------------------

    @Test
    void findByIdCollection_omitsUnreadableEdgesAndTheirEndpoints() {
        canRead(7L);
        // Edge 50 links two nodes in readable dataset 7; edge 51 reaches into unreadable dataset 9.
        when(edgeRepository.findAllByIdIn(anyCollection(), eq(EdgeEntity.class)))
                .thenReturn(List.of(edge(50L, 1L, 2L), edge(51L, 2L, 3L)));
        endpointsAre(nodeInDataset(1L, 7L), nodeInDataset(2L, 7L), nodeInDataset(3L, 9L));

        GraphDataWrapper<Resource, EdgeProxy> result = service.findByIdCollection(
                List.of(IdCollection.createFromId(50L), IdCollection.createFromId(51L)));

        assertThat(result.getRelations()).extracting(EdgeProxy::getId).containsExactly(50L);
        // Node 3 must not appear: it belongs only to the omitted edge, and returning it would
        // leak a resource from a dataset the caller cannot read.
        assertThat(result.getNodes()).extracting(Resource::getId)
                .containsExactlyInAnyOrder(1L, 2L);
    }

    @Test
    void findByIdCollection_returnsEverythingForAReadAllCaller() {
        canReadEverything();
        when(edgeRepository.findAllByIdIn(anyCollection(), eq(EdgeEntity.class)))
                .thenReturn(List.of(edge(50L, 1L, 2L), edge(51L, 2L, 3L)));
        endpointsAre(nodeInDataset(1L, 7L), nodeInDataset(2L, 7L), nodeInDataset(3L, 9L));

        GraphDataWrapper<Resource, EdgeProxy> result = service.findByIdCollection(
                List.of(IdCollection.createFromId(50L), IdCollection.createFromId(51L)));

        assertThat(result.getRelations()).extracting(EdgeProxy::getId)
                .containsExactlyInAnyOrder(50L, 51L);
        assertThat(result.getNodes()).extracting(Resource::getId)
                .containsExactlyInAnyOrder(1L, 2L, 3L);
    }
}
