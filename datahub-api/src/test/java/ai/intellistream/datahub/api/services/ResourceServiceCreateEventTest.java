// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.services;

import ai.intellistream.datahub.models.NodeModel;
import ai.intellistream.datahub.api.datasecurity.DataSecurity;
import ai.intellistream.datahub.api.datasecurity.DatasetClosureService;
import ai.intellistream.datahub.api.datasecurity.DatasetPermissions;
import ai.intellistream.datahub.api.datasecurity.TestDataSecurity;
import ai.intellistream.datahub.api.edge.EdgeMapper;
import ai.intellistream.datahub.api.messaging.events.ResourceCudPublishEvent;
import ai.intellistream.datahub.api.policy.PolicyEnforcement;
import ai.intellistream.datahub.api.responses.GraphDataWrapper;
import ai.intellistream.datahub.jpa.domains.ResourceEntity;
import ai.intellistream.datahub.models.RelForm;
import ai.intellistream.datahub.models.Resource;
import ai.intellistream.datahub.pulsar.EventObject;
import ai.intellistream.datahub.repositories.node.DataSetRepository;
import ai.intellistream.datahub.repositories.node.EdgeRepository;
import ai.intellistream.datahub.repositories.node.NodeRepository;
import ai.intellistream.datahub.repositories.node.RelationshipTypeRepository;
import ai.intellistream.datahub.repositories.subscription.SubscriptionRepository;
import ai.intellistream.datahub.services.LabelService;
import ai.intellistream.datahub.services.Neo4JService;
import ai.intellistream.datahub.services.NodeService;
import ai.intellistream.datahub.services.RelationshipTypeService;
import ai.intellistream.datahub.tenant.TenantContext;
import ai.intellistream.datahub.transformers.NodeReadMapper;
import jakarta.persistence.EntityManager;
import jakarta.validation.Validator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import java.util.Collections;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Safety net for the create path ahead of the create-side unification (M2 of the polymorphism
 * roadmap): a resource create must authorize the target dataset and publish exactly one
 * {@code RESOURCE_AND_RELATION} CUD event, and a denial must leave nothing persisted or
 * published. These invariants must survive every extraction unchanged.
 */
class ResourceServiceCreateEventTest {

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
    private DatasetPermissions permissions = DatasetPermissions.none();
    private final DataSecurity dataSecurity = TestDataSecurity.backedBy(() -> permissions);

    private final ResourceService service = new ResourceService(
            entityManager, nodeRepository, nodeService, labelService, edgeRepository,
            relationshipTypeRepository, relationshipTypeService, eventPublisher, neo4JService,
            dataSetRepository, dataSecurity, subscriptionRepository, validator, policyEnforcement,
            datasetClosureService,
            new EdgeMapper(nodeRepository, relationshipTypeRepository, relationshipTypeService),
            new ai.intellistream.datahub.api.services.node.NodeUpdateService(
                    nodeRepository, dataSetRepository, dataSecurity, labelService, nodeService, policyEnforcement));

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    private static GraphDataWrapper<NodeModel, RelForm> request(Long dataSetId) {
        Resource r = new Resource();
        r.setExternalId("pipe_1");
        r.setName("Pipe 1");
        r.setDataSetId(dataSetId);
        r.setLabels(List.of("PIPE"));
        GraphDataWrapper<NodeModel, RelForm> w = new GraphDataWrapper<>();
        w.getNodes().add(r);
        return w;
    }

    private static ResourceEntity entity(long id) {
        ResourceEntity e = new ResourceEntity();
        e.setId(id);
        e.setExternalId("pipe_1");
        e.setName("Pipe 1");
        e.setLabels("PIPE");
        return e;
    }

    @Test
    void createPublishesExactlyOneResourceAndRelationEvent() throws Exception {
        TenantContext.setTenantId("tenant-1");
        permissions = DatasetPermissions.of(false, false, Set.of(), Set.of(7L));
        when(validator.validate(any())).thenReturn(Collections.emptySet());
        ResourceEntity saved = entity(1L);
        when(nodeService.createFromResource(any())).thenReturn(saved);
        when(nodeRepository.saveAll(any())).thenReturn(List.of(saved));
        when(edgeRepository.saveAll(any())).thenReturn(List.of());

        service.create(request(7L));

        ArgumentCaptor<Object> cap = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher).publishEvent(cap.capture());
        ResourceCudPublishEvent event = (ResourceCudPublishEvent) cap.getValue();
        assertEquals(EventObject.RESOURCE_AND_RELATION, event.message().getEventObject());
    }

    @Test
    void aDenialLeavesNothingPersistedOrPublished() {
        TenantContext.setTenantId("tenant-1");
        permissions = DatasetPermissions.none();
        when(validator.validate(any())).thenReturn(Collections.emptySet());

        assertThrows(RuntimeException.class, () -> service.create(request(7L)));

        verify(nodeRepository, never()).saveAll(any());
        verify(eventPublisher, never()).publishEvent(any());
    }
}
