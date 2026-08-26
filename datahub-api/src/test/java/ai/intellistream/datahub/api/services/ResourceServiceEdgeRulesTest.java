// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.services;

import ai.intellistream.datahub.api.controllers.errors.BadRequestException;
import ai.intellistream.datahub.api.policy.PolicyEnforcement;
import ai.intellistream.datahub.api.datasecurity.DataSecurity;
import ai.intellistream.datahub.api.datasecurity.DatasetClosureService;
import ai.intellistream.datahub.jpa.domains.EdgeEntity;
import ai.intellistream.datahub.jpa.domains.NodeType;
import ai.intellistream.datahub.jpa.domains.RelationshipType;
import ai.intellistream.datahub.jpa.dto.EdgeEndpoint;
import ai.intellistream.datahub.jpa.dto.NameAndExternalIdDTO;
import ai.intellistream.datahub.models.RelForm;
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
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Endpoint rules for edge creation ({@code ResourceService.mapEdge} →
 * {@code assertEdgeEndpointsAllowed}): a relation TO a dataset must be BELONGS_TO, and a dataset
 * may connect to a timeseries only when the series has no dataset yet or already belongs to that
 * very dataset (the create-timeseries-inside-a-dataset flow makes exactly that edge). The console
 * form mirrors these checks, but this is the enforcement point for direct API calls.
 */
class ResourceServiceEdgeRulesTest {

    private final NodeRepository nodeRepository = mock(NodeRepository.class);
    private final RelationshipTypeService relationshipTypeService = mock(RelationshipTypeService.class);

    private final ResourceService service = new ResourceService(
            mock(EntityManager.class), nodeRepository, mock(NodeService.class),
            mock(LabelService.class), mock(EdgeRepository.class),
            mock(RelationshipTypeRepository.class), relationshipTypeService,
            mock(ApplicationEventPublisher.class), mock(Neo4JService.class),
            mock(DataSetRepository.class), mock(DataSecurity.class),
            mock(SubscriptionRepository.class), mock(Validator.class),
            mock(PolicyEnforcement.class), mock(DatasetClosureService.class),
                new ai.intellistream.datahub.transformers.NodeReadMapper(),
            new ai.intellistream.datahub.api.edge.EdgeMapper(nodeRepository, mock(RelationshipTypeRepository.class), relationshipTypeService),
            new ai.intellistream.datahub.api.services.node.NodeUpdateService(
                    mock(NodeRepository.class), mock(DataSetRepository.class), mock(DataSecurity.class),
                    mock(LabelService.class), mock(NodeService.class), mock(PolicyEnforcement.class),
                    java.util.List.of(new ai.intellistream.datahub.api.services.node.AssetUpdateStrategy())));

    /** Stub {@code mapEdge}'s resolution of an endpoint id, plus its rule-check projection. */
    private void node(long id, long nodeType, Long dataSetId) {
        when(nodeRepository.findById(eq(id), eq(NameAndExternalIdDTO.class)))
                .thenReturn(Optional.of(new NameAndExternalIdDTO(id, "node-" + id, "ext-" + id, id)));
        EdgeEndpoint.NodeTypeId type = mock(EdgeEndpoint.NodeTypeId.class);
        when(type.getId()).thenReturn(nodeType);
        EdgeEndpoint endpoint = mock(EdgeEndpoint.class);
        when(endpoint.getNodeType()).thenReturn(type);
        if (dataSetId != null) {
            EdgeEndpoint.DataSetId ds = mock(EdgeEndpoint.DataSetId.class);
            when(ds.getId()).thenReturn(dataSetId);
            when(endpoint.getDataSet()).thenReturn(ds);
        }
        when(nodeRepository.findById(eq(id), eq(EdgeEndpoint.class))).thenReturn(Optional.of(endpoint));
    }

    private RelForm rel(long fromId, long toId, String type) {
        RelationshipType relType = new RelationshipType();
        relType.setName(type);
        when(relationshipTypeService.findOrCreateByName(type)).thenReturn(relType);
        RelForm form = new RelForm();
        form.setFromId(fromId);
        form.setToId(toId);
        form.setRelationshipType(type);
        return form;
    }

    @Test
    void relationToDatasetMustBeBelongsTo() {
        node(1L, NodeType.RESOURCE, 9L);
        node(5L, NodeType.DATASET, null);

        assertThatThrownBy(() -> service.mapEdge(new EdgeEntity(), rel(1L, 5L, "PUBLISH_DATA_TO")))
                .isInstanceOf(BadRequestException.class);

        assertThatCode(() -> service.mapEdge(new EdgeEntity(), rel(1L, 5L, "BELONGS_TO")))
                .doesNotThrowAnyException();
    }

    @Test
    void datasetMayNotClaimATimeseriesOfAnotherDataset() {
        node(5L, NodeType.DATASET, null);
        node(2L, NodeType.TIMESERIES, 9L); // already in dataset 9

        assertThatThrownBy(() -> service.mapEdge(new EdgeEntity(), rel(5L, 2L, "BELONGS_TO")))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void datasetMayConnectItsOwnOrAnUnhomedTimeseries() {
        node(5L, NodeType.DATASET, null);
        node(2L, NodeType.TIMESERIES, 5L);   // its own membership edge (the create-inside flow)
        node(3L, NodeType.TIMESERIES, null); // no dataset yet

        assertThatCode(() -> service.mapEdge(new EdgeEntity(), rel(5L, 2L, "BELONGS_TO")))
                .doesNotThrowAnyException();
        assertThatCode(() -> service.mapEdge(new EdgeEntity(), rel(5L, 3L, "BELONGS_TO")))
                .doesNotThrowAnyException();
    }

    @Test
    void ordinaryResourceMayConnectToATimeseriesInADataset() {
        node(1L, NodeType.RESOURCE, 9L);
        node(2L, NodeType.TIMESERIES, 9L);

        assertThatCode(() -> service.mapEdge(new EdgeEntity(), rel(1L, 2L, "PUBLISH_DATA_TO")))
                .doesNotThrowAnyException();
    }
}
