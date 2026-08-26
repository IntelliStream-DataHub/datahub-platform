// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.services;

import ai.intellistream.datahub.models.NodeModel;
import ai.intellistream.datahub.api.datasecurity.DataSecurity;
import ai.intellistream.datahub.api.datasecurity.DatasetAccessDeniedException;
import ai.intellistream.datahub.api.datasecurity.DatasetClosureService;
import ai.intellistream.datahub.api.datasecurity.DatasetPermissions;
import ai.intellistream.datahub.api.datasecurity.TestDataSecurity;
import ai.intellistream.datahub.api.policy.PolicyEnforcement;
import ai.intellistream.datahub.api.responses.GraphDataWrapper;
import ai.intellistream.datahub.jpa.domains.DatasetEntity;
import ai.intellistream.datahub.jpa.domains.NodeEntity;
import ai.intellistream.datahub.jpa.domains.PolicyEntity;
import ai.intellistream.datahub.jpa.domains.ResourceEntity;
import ai.intellistream.datahub.jpa.dto.NameAndExternalIdDTO;
import ai.intellistream.datahub.models.EdgeProxy;
import ai.intellistream.datahub.models.RelForm;
import ai.intellistream.datahub.models.Resource;
import ai.intellistream.datahub.models.UpdateRelForm;
import ai.intellistream.datahub.models.UpdateResourceForm;
import ai.intellistream.datahub.repositories.node.DataSetRepository;
import ai.intellistream.datahub.repositories.node.EdgeRepository;
import ai.intellistream.datahub.repositories.node.NodeRepository;
import ai.intellistream.datahub.repositories.node.RelationshipTypeRepository;
import ai.intellistream.datahub.repositories.subscription.SubscriptionRepository;
import ai.intellistream.datahub.services.LabelService;
import ai.intellistream.datahub.services.Neo4JService;
import ai.intellistream.datahub.services.NodeService;
import ai.intellistream.datahub.services.RelationshipTypeService;
import jakarta.persistence.EntityManager;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Manage-grant coverage for dataset and policy nodes reached through the generic {@code /resources}
 * pipeline.
 *
 * <p>{@code /datasets} and {@code /policies} gate every mutation of these nodes on the all-datasets
 * write grant ({@link DataSecurity#assertCanManageDataSets()}): a dataset is the unit access is
 * granted on, so its lifecycle is management, not data entry. {@code /resources} reaches the same
 * rows and must apply the same rule. Before these gates existed, a caller with a single per-dataset
 * write grant could mint a DATASET- or POLICY-labelled node via {@code /resources/create} carrying
 * a {@code data_set_id} — and, because the minted node was then not an orphan, keep updating and
 * deleting it under that same grant.
 */
class ResourceServiceNodeTypeAclTest {

    private final EntityManager entityManager = mock(EntityManager.class);
    private final NodeRepository nodeRepository = mock(NodeRepository.class);
    private final NodeService nodeService = mock(NodeService.class);
    private final LabelService labelService = mock(LabelService.class);
    private final EdgeRepository edgeRepository = mock(EdgeRepository.class);
    private final RelationshipTypeRepository relationshipTypeRepository = mock(RelationshipTypeRepository.class);
    private final RelationshipTypeService relationshipTypeService = mock(RelationshipTypeService.class);
    private final ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
    private final Neo4JService neo4JService = mock(Neo4JService.class);
    private final DataSetRepository dataSetRepository = mock(DataSetRepository.class);
    private final SubscriptionRepository subscriptionRepository = mock(SubscriptionRepository.class);
    private final Validator validator = mock(Validator.class);
    private final PolicyEnforcement policyEnforcement = mock(PolicyEnforcement.class);
    private final DatasetClosureService datasetClosureService = mock(DatasetClosureService.class);
    // Grants stated as dataset ids; see TestDataSecurity for why.
    private DatasetPermissions permissions = DatasetPermissions.none();
    private final DataSecurity dataSecurity = TestDataSecurity.backedBy(() -> permissions);

    private final ResourceService service = new ResourceService(
            entityManager, nodeRepository, nodeService, edgeRepository,
            relationshipTypeRepository, relationshipTypeService, eventPublisher, neo4JService, dataSecurity, subscriptionRepository, validator, policyEnforcement,
            datasetClosureService,
                new ai.intellistream.datahub.api.edge.EdgeMapper(nodeRepository, relationshipTypeRepository, relationshipTypeService),
            new ai.intellistream.datahub.api.services.node.NodeUpdateService(
                    nodeRepository, dataSetRepository, dataSecurity, labelService, nodeService, policyEnforcement));

    /** The caller may write these datasets, and nothing else — no manage grant. */
    private void canWrite(Long... datasetIds) {
        permissions = DatasetPermissions.of(false, false, Set.of(), Set.of(datasetIds));
    }

    /** The all-datasets write grant — the manage grant /datasets and /policies require. */
    private void canManage() {
        permissions = DatasetPermissions.of(false, true, Set.of(), Set.of());
    }

    /** A node of the given entity type living in {@code datasetId} (null → orphan). */
    private static <T extends NodeEntity> T nodeInDataset(Class<T> type, long nodeId, Long datasetId) {
        T node = mock(type);
        when(node.getId()).thenReturn(nodeId);
        when(node.getExternalId()).thenReturn("node_" + nodeId);
        when(node.getMetadata()).thenReturn(new HashMap<>());
        if (datasetId != null) {
            DatasetEntity ds = mock(DatasetEntity.class);
            when(ds.getId()).thenReturn(datasetId);
            when(node.getDataSet()).thenReturn(ds);
        }
        return node;
    }

    private static GraphDataWrapper<NodeModel, RelForm> createRequest(Long dataSetId, String... labels) {
        Resource resource = new Resource();
        resource.setExternalId("new_node");
        resource.setName("new node");
        resource.setDataSetId(dataSetId);
        resource.setLabels(List.of(labels));
        GraphDataWrapper<NodeModel, RelForm> req = new GraphDataWrapper<>();
        req.getNodes().add(resource);
        return req;
    }

    private static GraphDataWrapper<UpdateResourceForm, UpdateRelForm> updateRequest(long nodeId) {
        GraphDataWrapper<UpdateResourceForm, UpdateRelForm> req = new GraphDataWrapper<>();
        req.getNodes().add(new UpdateResourceForm(nodeId));
        return req;
    }

    private static GraphDataWrapper<Resource, EdgeProxy> deleteRequest(long nodeId) {
        Resource resource = new Resource();
        resource.setId(nodeId);
        GraphDataWrapper<Resource, EdgeProxy> req = new GraphDataWrapper<>();
        req.getNodes().add(resource);
        return req;
    }

    // ---- create ------------------------------------------------------------------------------

    /**
     * The hole these gates close: a DATASET-labelled create with a {@code data_set_id} the caller
     * can write used to pass — minting a non-orphan dataset node the same grant could then mutate.
     */
    @Test
    void deniesCreatingADatasetNodeWithOnlyPerDatasetWrite() {
        canWrite(7L);
        when(validator.validate(any())).thenReturn(Collections.emptySet());

        assertThatThrownBy(() -> service.create(createRequest(7L, "DATASET")))
                .isInstanceOf(DatasetAccessDeniedException.class);

        verify(nodeService, never()).createFromResource(any());
        verify(nodeRepository, never()).saveAll(any());
    }

    /** Label names canonicalise before dispatch, so a lowercase type-label must gate the same. */
    @Test
    void deniesCreatingAPolicyNodeViaLowercaseLabel() {
        canWrite(7L);
        when(validator.validate(any())).thenReturn(Collections.emptySet());

        assertThatThrownBy(() -> service.create(createRequest(7L, "policy")))
                .isInstanceOf(DatasetAccessDeniedException.class);

        verify(nodeService, never()).createFromResource(any());
    }

    /** The gate must not over-fire: near-miss labels are ordinary labels, not type-labels. */
    @Test
    void allowsCreatingAPlainResourceWithPerDatasetWrite() {
        canWrite(7L);
        when(validator.validate(any())).thenReturn(Collections.emptySet());
        NodeEntity created = nodeInDataset(ResourceEntity.class, 5L, 7L);
        when(nodeService.createFromResource(any())).thenReturn(created);
        when(nodeRepository.saveAll(any())).thenReturn(List.of(created));
        when(edgeRepository.saveAll(any())).thenReturn(List.of());

        assertThatCode(() -> service.create(createRequest(7L, "Pump", "datasets")))
                .doesNotThrowAnyException();

        verify(nodeRepository).saveAll(any());
    }

    @Test
    void allowsCreatingADatasetNodeWithTheManageGrant() {
        canManage();
        when(validator.validate(any())).thenReturn(Collections.emptySet());
        NodeEntity created = nodeInDataset(DatasetEntity.class, 5L, null);
        when(nodeService.createFromResource(any())).thenReturn(created);
        when(nodeRepository.saveAll(any())).thenReturn(List.of(created));
        when(edgeRepository.saveAll(any())).thenReturn(List.of());

        assertThatCode(() -> service.create(createRequest(null, "DATASET")))
                .doesNotThrowAnyException();

        verify(nodeRepository).saveAll(any());
    }

    // ---- update ------------------------------------------------------------------------------

    /**
     * The second half of the closed hole: a dataset node that carries a {@code data_set_id} is not
     * an orphan, so plain {@code assertCanWrite} passes on a per-dataset grant — the explicit gate
     * must still deny.
     */
    @Test
    void deniesUpdatingADatasetNodeEvenWhenItsDatasetIsWritable() {
        canWrite(7L);
        NodeEntity node = nodeInDataset(DatasetEntity.class, 5L, 7L);
        when(nodeRepository.findById(5L)).thenReturn(Optional.of(node));

        assertThatThrownBy(() -> service.update(updateRequest(5L)))
                .isInstanceOf(DatasetAccessDeniedException.class);

        verify(nodeRepository, never()).saveAll(any());
    }

    @Test
    void deniesUpdatingAPolicyNodeEvenWhenItsDatasetIsWritable() {
        canWrite(7L);
        NodeEntity node = nodeInDataset(PolicyEntity.class, 5L, 7L);
        when(nodeRepository.findById(5L)).thenReturn(Optional.of(node));

        assertThatThrownBy(() -> service.update(updateRequest(5L)))
                .isInstanceOf(DatasetAccessDeniedException.class);

        verify(nodeRepository, never()).saveAll(any());
    }

    @Test
    void allowsUpdatingAPlainResourceWithPerDatasetWrite() {
        canWrite(7L);
        NodeEntity node = nodeInDataset(ResourceEntity.class, 5L, 7L);
        when(nodeRepository.findById(5L)).thenReturn(Optional.of(node));
        when(labelService.resolveLabelUpdate(any(), any())).thenReturn(Optional.empty());
        when(nodeRepository.saveAll(any())).thenReturn(List.of(node));
        when(edgeRepository.saveAll(any())).thenReturn(List.of());

        assertThatCode(() -> service.update(updateRequest(5L))).doesNotThrowAnyException();

        verify(nodeRepository).saveAll(any());
    }

    @Test
    void allowsUpdatingADatasetNodeWithTheManageGrant() {
        canManage();
        NodeEntity node = nodeInDataset(DatasetEntity.class, 5L, null);
        when(nodeRepository.findById(5L)).thenReturn(Optional.of(node));
        when(labelService.resolveLabelUpdate(any(), any())).thenReturn(Optional.empty());
        when(nodeRepository.saveAll(any())).thenReturn(List.of(node));
        when(edgeRepository.saveAll(any())).thenReturn(List.of());

        assertThatCode(() -> service.update(updateRequest(5L))).doesNotThrowAnyException();

        verify(nodeRepository).saveAll(any());
    }

    // ---- delete ------------------------------------------------------------------------------

    @Test
    void deniesDeletingADatasetNodeEvenWhenItsDatasetIsWritable() {
        canWrite(7L);
        when(nodeRepository.findAllByExternalIdHashIn(any(), eq(NameAndExternalIdDTO.class)))
                .thenReturn(List.of());
        NodeEntity node = nodeInDataset(DatasetEntity.class, 5L, 7L);
        when(nodeRepository.findAllById(any())).thenReturn(List.of(node));

        assertThatThrownBy(() -> service.delete(deleteRequest(5L)))
                .isInstanceOf(DatasetAccessDeniedException.class);
    }

    @Test
    void deniesDeletingAPolicyNodeEvenWhenItsDatasetIsWritable() {
        canWrite(7L);
        when(nodeRepository.findAllByExternalIdHashIn(any(), eq(NameAndExternalIdDTO.class)))
                .thenReturn(List.of());
        NodeEntity node = nodeInDataset(PolicyEntity.class, 5L, 7L);
        when(nodeRepository.findAllById(any())).thenReturn(List.of(node));

        assertThatThrownBy(() -> service.delete(deleteRequest(5L)))
                .isInstanceOf(DatasetAccessDeniedException.class);
    }

    // ---- edges -------------------------------------------------------------------------------

    /**
     * Re-wiring the hierarchy is management too: an edge onto a dataset node needs the manage
     * grant even when the endpoint's own dataset is writable, rather than relying on dataset
     * nodes being orphans (which a node minted with a {@code data_set_id} is not).
     */
    @Test
    void deniesCreatingAnEdgeOntoADatasetNodeWithoutTheManageGrant() {
        canWrite(7L);
        when(validator.validate(any())).thenReturn(Collections.emptySet());
        when(nodeRepository.saveAll(any())).thenReturn(List.of());
        when(nodeRepository.findById(eq(1L), eq(NameAndExternalIdDTO.class)))
                .thenReturn(Optional.of(new NameAndExternalIdDTO(1L, "node-1", "ext-1", 1L)));
        when(nodeRepository.findById(eq(2L), eq(NameAndExternalIdDTO.class)))
                .thenReturn(Optional.of(new NameAndExternalIdDTO(2L, "node-2", "ext-2", 2L)));
        NodeEntity plainEndpoint = nodeInDataset(ResourceEntity.class, 1L, 7L);
        NodeEntity datasetEndpoint = nodeInDataset(DatasetEntity.class, 2L, 7L);
        when(nodeRepository.findAllByIdIn(anyCollection(), eq(NodeEntity.class)))
                .thenReturn(List.of(plainEndpoint, datasetEndpoint));

        RelForm rel = new RelForm();
        rel.setFromId(1L);
        rel.setToId(2L);
        rel.setRelationshipType("BELONGS_TO");
        GraphDataWrapper<NodeModel, RelForm> req = new GraphDataWrapper<>();
        req.getRelations().add(rel);

        assertThatThrownBy(() -> service.create(req))
                .isInstanceOf(DatasetAccessDeniedException.class);

        verify(edgeRepository, never()).saveAll(any());
    }
}
