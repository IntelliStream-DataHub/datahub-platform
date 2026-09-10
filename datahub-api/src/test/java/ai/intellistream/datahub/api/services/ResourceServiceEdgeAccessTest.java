// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.services;

import ai.intellistream.datahub.api.messaging.outbox.GraphOutbox;
import ai.intellistream.datahub.models.NodeModel;
import ai.intellistream.datahub.api.policy.PolicyEnforcement;
import ai.intellistream.datahub.api.datasecurity.DataSecurity;
import ai.intellistream.datahub.api.datasecurity.DatasetClosureService;
import ai.intellistream.datahub.api.datasecurity.DatasetPermissions;
import ai.intellistream.datahub.api.datasecurity.TestDataSecurity;
import ai.intellistream.datahub.api.responses.GraphDataWrapper;
import ai.intellistream.datahub.jpa.domains.DatasetEntity;
import ai.intellistream.datahub.jpa.domains.EdgeEntity;
import ai.intellistream.datahub.jpa.domains.NodeEntity;
import ai.intellistream.datahub.jpa.dto.NameAndExternalIdDTO;
import ai.intellistream.datahub.models.EdgeProxy;
import ai.intellistream.datahub.models.RelForm;
import ai.intellistream.datahub.models.Resource;
import ai.intellistream.datahub.models.UpdateRelForm;
import ai.intellistream.datahub.models.UpdateResourceForm;
import ai.intellistream.datahub.repositories.node.AssetRepository;
import ai.intellistream.datahub.repositories.node.DataSetRepository;
import ai.intellistream.datahub.repositories.node.EdgeRepository;
import ai.intellistream.datahub.repositories.node.FunctionRepository;
import ai.intellistream.datahub.repositories.node.NodeRepository;
import ai.intellistream.datahub.repositories.node.RelationshipTypeRepository;
import ai.intellistream.datahub.repositories.node.ResourceRepository;
import ai.intellistream.datahub.repositories.node.TimeseriesRepository;
import ai.intellistream.datahub.repositories.subscription.SubscriptionRepository;
import ai.intellistream.datahub.services.LabelService;
import ai.intellistream.datahub.services.Neo4JService;
import ai.intellistream.datahub.services.NodeService;
import ai.intellistream.datahub.services.RelationshipTypeService;
import jakarta.persistence.EntityManager;
import jakarta.validation.Validator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Dataset-ACL coverage for edge mutations in {@link ResourceService}.
 *
 * <p>An edge carries no dataset of its own, so it is authorised on its two endpoint nodes, and a
 * caller must be able to write <em>both</em>. Requiring both is what closes the escalation created
 * by read and write being independent grants: a caller with write but no read on a node could
 * otherwise attach it beneath a dataset they can read and inherit read on it through the
 * {@code BELONGS_TO} hierarchy.
 *
 * <p>Before this check existed, {@code relations[]} was authorised nowhere at all — only the
 * {@code nodes[]} submitted alongside were checked — so any node could be linked to any other, and
 * any edge id in the tenant could be deleted.
 */
class ResourceServiceEdgeAccessTest {

    private final EntityManager entityManager = mock(EntityManager.class);
    private final NodeRepository nodeRepository = mock(NodeRepository.class);
    private final NodeService nodeService = mock(NodeService.class);
    private final LabelService labelService = mock(LabelService.class);
    private final EdgeRepository edgeRepository = mock(EdgeRepository.class);
    private final RelationshipTypeRepository relationshipTypeRepository = mock(RelationshipTypeRepository.class);
    private final RelationshipTypeService relationshipTypeService = mock(RelationshipTypeService.class);
    private final ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
    private final GraphOutbox graphOutbox = mock(GraphOutbox.class);
    private final Neo4JService neo4JService = mock(Neo4JService.class);
    private final DataSetRepository dataSetRepository = mock(DataSetRepository.class);
    private final ResourceRepository resourceRepository = mock(ResourceRepository.class);
    private final TimeseriesRepository timeseriesRepository = mock(TimeseriesRepository.class);
    private final AssetRepository assetRepository = mock(AssetRepository.class);
    private final FunctionRepository functionRepository = mock(FunctionRepository.class);
    private final SubscriptionRepository subscriptionRepository = mock(SubscriptionRepository.class);
    private final Validator validator = mock(Validator.class);
    // Naming policy is exercised by its own tests; here it must simply not reject, so the
    // mock's default empty-list return is exactly the behaviour these tests want.
    private final PolicyEnforcement policyEnforcement = mock(PolicyEnforcement.class);
    private final DatasetClosureService datasetClosureService = mock(DatasetClosureService.class);
    // Grants stated as dataset ids; see TestDataSecurity for why.
    private DatasetPermissions permissions = DatasetPermissions.none();
    private final DataSecurity dataSecurity = TestDataSecurity.backedBy(() -> permissions);

    private final ResourceService service = new ResourceService(
            entityManager, nodeRepository, nodeService, edgeRepository,
            relationshipTypeRepository, relationshipTypeService, eventPublisher, graphOutbox, neo4JService,
            dataSecurity, subscriptionRepository, validator, policyEnforcement,
            datasetClosureService,
            mock(IngestQuotaService.class), mock(TenantLimitsService.class),
            new ai.intellistream.datahub.api.edge.EdgeMapper(nodeRepository, relationshipTypeRepository, relationshipTypeService),
            new ai.intellistream.datahub.api.services.node.NodeUpdateService(
                    nodeRepository, dataSetRepository, dataSecurity, labelService, nodeService, policyEnforcement),
            mock(ai.intellistream.datahub.api.policy.NamingPolicyResolver.class));

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    /** The caller may write these datasets (and, unless stated, read nothing). */
    private void canWrite(Long... datasetIds) {
        permissions = DatasetPermissions.of(false, false, Set.of(), Set.of(datasetIds));
    }

    /** Write on some datasets, read on others — the shape the escalation test needs. */
    private void canWriteAndRead(Set<Long> writable, Set<Long> readable) {
        permissions = DatasetPermissions.of(false, false, readable, writable);
    }

    private static void authWith(String... roles) {
        List<GrantedAuthority> authorities = Arrays.stream(roles)
                .map(r -> (GrantedAuthority) new SimpleGrantedAuthority(r))
                .toList();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("user", "pw", authorities));
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

    /** Stub the endpoint lookup that {@code assertCanWriteNodes} performs. */
    private void endpointsAre(NodeEntity... nodes) {
        when(nodeRepository.findAllByIdIn(anyCollection(), eq(NodeEntity.class)))
                .thenReturn(List.of(nodes));
    }

    /** Stub {@code mapEdge}'s resolution of an endpoint id to a node. */
    private void resolvableNode(long id) {
        when(nodeRepository.findById(eq(id), eq(NameAndExternalIdDTO.class)))
                .thenReturn(Optional.of(new NameAndExternalIdDTO(id, "node-" + id, "ext-" + id, id)));
    }

    private static GraphDataWrapper<NodeModel, RelForm> linkRequest(long fromId, long toId) {
        RelForm rel = new RelForm();
        rel.setFromId(fromId);
        rel.setToId(toId);
        rel.setRelationshipType("BELONGS_TO");
        GraphDataWrapper<NodeModel, RelForm> req = new GraphDataWrapper<>();
        req.getRelations().add(rel);
        return req;
    }

    // ---- create ------------------------------------------------------------------------------

    @Test
    @SuppressWarnings("unchecked")
    void deniesCreatingAnEdgeWhenNeitherEndpointIsWritable() throws Exception {
        canWrite(7L);
        when(validator.validate(any())).thenReturn(Collections.emptySet());
        when(nodeRepository.saveAll(any())).thenReturn(List.of());
        resolvableNode(1L);
        resolvableNode(2L);
        // Both endpoints sit in dataset 9, which the caller cannot write.
        endpointsAre(nodeInDataset(1L, 9L), nodeInDataset(2L, 9L));

        assertThatThrownBy(() -> service.create(linkRequest(1L, 2L)))
                .isInstanceOf(AccessDeniedException.class);

        verify(edgeRepository, never()).saveAll(any());
    }

    /**
     * The escalation this check exists for. The caller can write node 1 (dataset 7) but only
     * <em>read</em> dataset 9, so linking 1 beneath 2 to inherit read must be refused.
     */
    @Test
    void deniesCreatingAnEdgeWhenOnlyOneEndpointIsWritable() {
        canWriteAndRead(Set.of(7L), Set.of(9L));
        when(validator.validate(any())).thenReturn(Collections.emptySet());
        when(nodeRepository.saveAll(any())).thenReturn(List.of());
        resolvableNode(1L);
        resolvableNode(2L);
        endpointsAre(nodeInDataset(1L, 7L), nodeInDataset(2L, 9L));

        assertThatThrownBy(() -> service.create(linkRequest(1L, 2L)))
                .isInstanceOf(AccessDeniedException.class);

        verify(edgeRepository, never()).saveAll(any());
    }

    @Test
    void allowsCreatingAnEdgeWhenBothEndpointsAreWritable() throws Exception {
        canWrite(7L, 9L);
        when(validator.validate(any())).thenReturn(Collections.emptySet());
        when(nodeRepository.saveAll(any())).thenReturn(List.of());
        when(edgeRepository.saveAll(any())).thenReturn(List.of());
        resolvableNode(1L);
        resolvableNode(2L);
        endpointsAre(nodeInDataset(1L, 7L), nodeInDataset(2L, 9L));

        service.create(linkRequest(1L, 2L));

        // Got past the ACL: the edge was persisted rather than rejected.
        verify(edgeRepository).saveAll(any());
    }

    // ---- update ------------------------------------------------------------------------------

    /**
     * Re-pointing an edge mutates the graph at the old endpoints too, so those must be authorised
     * even though the request only names the new ones.
     */
    @Test
    void deniesRepointingAnEdgeAwayFromAnUnwritableEndpoint() {
        canWrite(7L);

        EdgeEntity existing = new EdgeEntity();
        existing.setId(50L);
        existing.setStart(1L);   // dataset 7, writable
        existing.setEnd(99L);    // dataset 9, NOT writable — the endpoint being moved away from
        when(edgeRepository.findById(50L)).thenReturn(Optional.of(existing));

        // The no-arg constructor leaves `update` null; this one initialises the RelFields.
        UpdateRelForm form = new UpdateRelForm(50L);
        form.getUpdate().getEnd().set(2L);   // re-point onto node 2, in writable dataset 7

        GraphDataWrapper<UpdateResourceForm, UpdateRelForm> req = new GraphDataWrapper<>();
        req.getRelations().add(form);
        when(nodeRepository.saveAll(any())).thenReturn(List.of());
        endpointsAre(nodeInDataset(1L, 7L), nodeInDataset(2L, 7L), nodeInDataset(99L, 9L));

        assertThatThrownBy(() -> service.update(req))
                .isInstanceOf(AccessDeniedException.class);

        verify(edgeRepository, never()).saveAll(any());
    }

    // ---- delete ------------------------------------------------------------------------------

    @Test
    void deniesDeletingAnEdgeWhoseEndpointIsNotWritable() {
        canWrite(7L);

        EdgeEntity edge = new EdgeEntity();
        edge.setId(50L);
        edge.setStart(1L);
        edge.setEnd(99L);
        when(edgeRepository.findAllByIdIn(any(), eq(EdgeEntity.class))).thenReturn(List.of(edge));
        endpointsAre(nodeInDataset(1L, 7L), nodeInDataset(99L, 9L));

        GraphDataWrapper<Resource, EdgeProxy> req = new GraphDataWrapper<>();
        req.getRelations().add(new EdgeProxy(50L));

        assertThatThrownBy(() -> service.delete(req))
                .isInstanceOf(AccessDeniedException.class);

        verify(edgeRepository, never()).deleteAllById(any());
    }
}
