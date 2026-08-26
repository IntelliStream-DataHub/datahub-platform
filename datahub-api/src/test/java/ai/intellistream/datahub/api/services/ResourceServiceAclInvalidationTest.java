// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.services;

import ai.intellistream.datahub.api.messaging.outbox.GraphOutbox;
import ai.intellistream.datahub.models.NodeModel;
import ai.intellistream.datahub.api.policy.PolicyEnforcement;
import ai.intellistream.datahub.api.datasecurity.DataSecurity;
import ai.intellistream.datahub.api.datasecurity.DatasetClosureService;
import ai.intellistream.datahub.api.datasecurity.TestDataSecurity;
import ai.intellistream.datahub.api.messaging.events.DatasetAclInvalidationEvent;
import ai.intellistream.datahub.api.responses.GraphDataWrapper;
import ai.intellistream.datahub.asset.ResourceNetwork;
import ai.intellistream.datahub.jpa.domains.AssetEntity;
import ai.intellistream.datahub.jpa.domains.DatasetEntity;
import ai.intellistream.datahub.jpa.domains.EdgeEntity;
import ai.intellistream.datahub.jpa.domains.NodeEntity;
import ai.intellistream.datahub.jpa.domains.RelationshipType;
import ai.intellistream.datahub.jpa.dto.NameAndExternalIdDTO;
import ai.intellistream.datahub.models.EdgeProxy;
import ai.intellistream.datahub.models.RelForm;
import ai.intellistream.datahub.models.Resource;
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
import ai.intellistream.datahub.tenant.TenantContext;
import jakarta.persistence.EntityManager;
import jakarta.validation.Validator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isA;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * When {@link ResourceService} must invalidate the tenant's cached dataset-ACL closures.
 *
 * <p>Grants name datasets by {@code externalId} and expand down the {@code BELONGS_TO} hierarchy,
 * so a dataset appearing, disappearing or being renamed, or a hierarchy edge changing, alters what
 * an unchanged grant covers. The other half matters just as much: ordinary resource writes must
 * <em>not</em> bump the generation, or the cache is worthless.
 */
class ResourceServiceAclInvalidationTest {

    private static final String TENANT = "tenant-acme";

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
    private final DataSecurity dataSecurity = TestDataSecurity.readingAndWritingEverything();

    private final ResourceService service = new ResourceService(
            entityManager, nodeRepository, nodeService, labelService, edgeRepository,
            relationshipTypeRepository, relationshipTypeService, eventPublisher, graphOutbox, neo4JService,
            dataSetRepository, dataSecurity, subscriptionRepository, validator, policyEnforcement,
            datasetClosureService,
                new ai.intellistream.datahub.transformers.NodeReadMapper(),
            new ai.intellistream.datahub.api.edge.EdgeMapper(nodeRepository, relationshipTypeRepository, relationshipTypeService));

    @BeforeEach
    void setUp() {
        TenantContext.setTenantId(TENANT);
        when(validator.validate(any())).thenReturn(Collections.emptySet());
        when(nodeRepository.saveAll(any())).thenReturn(List.of());
        when(edgeRepository.saveAll(any())).thenReturn(List.of());
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private void createdNodeIs(NodeEntity entity) {
        when(nodeService.createFromResource(any())).thenReturn(entity);
    }

    private void resolvableNode(long id) {
        when(nodeRepository.findById(eq(id), eq(NameAndExternalIdDTO.class)))
                .thenReturn(Optional.of(new NameAndExternalIdDTO(id, "n" + id, "ext" + id, id)));
    }

    private static GraphDataWrapper<NodeModel, RelForm> createWithNode() {
        GraphDataWrapper<NodeModel, RelForm> req = new GraphDataWrapper<>();
        Resource r = new Resource();
        r.setExternalId("some_resource");
        r.setName("Some Resource");
        req.getNodes().add(r);
        return req;
    }

    private GraphDataWrapper<NodeModel, RelForm> createWithEdge(String relationshipType) {
        resolvableNode(1L);
        resolvableNode(2L);
        RelationshipType type = new RelationshipType();
        type.setName(relationshipType);
        when(relationshipTypeService.findOrCreateByName(relationshipType)).thenReturn(type);

        RelForm rel = new RelForm();
        rel.setFromId(1L);
        rel.setToId(2L);
        rel.setRelationshipType(relationshipType);
        GraphDataWrapper<NodeModel, RelForm> req = new GraphDataWrapper<>();
        req.getRelations().add(rel);
        return req;
    }

    private void assertInvalidated() {
        verify(eventPublisher).publishEvent(isA(DatasetAclInvalidationEvent.class));
    }

    private void assertNotInvalidated() {
        verify(eventPublisher, never()).publishEvent(isA(DatasetAclInvalidationEvent.class));
    }

    // ---- create ------------------------------------------------------------------------------

    @Test
    void creatingADatasetInvalidates() throws Exception {
        createdNodeIs(new DatasetEntity());

        service.create(createWithNode());

        assertInvalidated();
    }

    /** The case that keeps the cache useful: ordinary resource writes must not bump the generation. */
    @Test
    void creatingAnOrdinaryResourceDoesNotInvalidate() throws Exception {
        createdNodeIs(new AssetEntity());

        service.create(createWithNode());

        assertNotInvalidated();
    }

    @Test
    void creatingABelongsToEdgeInvalidates() throws Exception {
        service.create(createWithEdge("BELONGS_TO"));

        assertInvalidated();
    }

    @Test
    void creatingAnUnrelatedEdgeDoesNotInvalidate() throws Exception {
        service.create(createWithEdge("FLOWS_TO"));

        assertNotInvalidated();
    }

    // ---- delete ------------------------------------------------------------------------------

    @Test
    void deletingADatasetInvalidates() throws Exception {
        DatasetEntity dataset = new DatasetEntity();
        dataset.setId(5L);
        when(nodeRepository.findAllById(any())).thenReturn(List.of(dataset));
        when(edgeRepository.findAllByStartInOrEndIn(any(), any(), any())).thenReturn(List.of());
        when(edgeRepository.findAllByIdIn(any(), eq(EdgeEntity.class))).thenReturn(List.of());
        when(neo4JService.fetchComponentForNodes(any()))
                .thenReturn(new ResourceNetwork(Set.of(), Set.of(), Set.of()));

        GraphDataWrapper<Resource, EdgeProxy> req = new GraphDataWrapper<>();
        Resource r = new Resource();
        r.setId(5L);
        req.getNodes().add(r);

        service.delete(req);

        assertInvalidated();
    }

    @Test
    void deletingAnOrdinaryResourceDoesNotInvalidate() throws Exception {
        AssetEntity asset = new AssetEntity();
        asset.setId(5L);
        when(nodeRepository.findAllById(any())).thenReturn(List.of(asset));
        when(edgeRepository.findAllByStartInOrEndIn(any(), any(), any())).thenReturn(List.of());
        when(edgeRepository.findAllByIdIn(any(), eq(EdgeEntity.class))).thenReturn(List.of());
        when(neo4JService.fetchComponentForNodes(any()))
                .thenReturn(new ResourceNetwork(Set.of(), Set.of(), Set.of()));

        GraphDataWrapper<Resource, EdgeProxy> req = new GraphDataWrapper<>();
        Resource r = new Resource();
        r.setId(5L);
        req.getNodes().add(r);

        service.delete(req);

        assertNotInvalidated();
    }
}
