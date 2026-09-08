// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.services;

import ai.intellistream.datahub.api.messaging.outbox.GraphOutbox;
import ai.intellistream.datahub.api.datasecurity.DataSecurity;
import ai.intellistream.datahub.api.datasecurity.DatasetClosureService;
import ai.intellistream.datahub.api.policy.PolicyEnforcement;
import ai.intellistream.datahub.asset.ResourceNetwork;
import ai.intellistream.datahub.errors.ObjectNotFoundException;
import ai.intellistream.datahub.jpa.domains.AssetEntity;
import ai.intellistream.datahub.jpa.domains.NodeEntity;
import ai.intellistream.datahub.models.FetchNearestResourcesForm;
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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code POST /resources/fetch-nearest} accepts an {@code externalId} as well as an {@code id}.
 *
 * <p>The form has always declared both and its {@code @OneIdNotNull} validator has always accepted
 * either, but the endpoint only ever read {@code id}, so an externalId-only request — a valid one,
 * by the API's own contract — reached {@code findById(null)} and came back as a 500. That is worse
 * than a plain rejection: it tells the caller the server is broken when their request was fine.
 *
 * <p>{@code fetch-related} has resolved both identifiers all along, so this also stops two sibling
 * endpoints disagreeing about what an identifier is.
 */
class ResourceServiceFetchNearestIdentifierTest {

    private final NodeRepository nodeRepository = mock(NodeRepository.class);
    private final Neo4JService neo4JService = mock(Neo4JService.class);
    private final DataSecurity dataSecurity = mock(DataSecurity.class);

    private final ResourceService service = new ResourceService(
            mock(EntityManager.class), nodeRepository, mock(NodeService.class),
            mock(EdgeRepository.class),
            mock(RelationshipTypeRepository.class), mock(RelationshipTypeService.class),
            mock(ApplicationEventPublisher.class), mock(GraphOutbox.class), neo4JService,
            dataSecurity,
            mock(SubscriptionRepository.class), mock(Validator.class),
            mock(PolicyEnforcement.class), mock(DatasetClosureService.class),
            mock(IngestQuotaService.class), mock(TenantLimitsService.class),
            new ai.intellistream.datahub.api.edge.EdgeMapper(
                    nodeRepository, mock(RelationshipTypeRepository.class), mock(RelationshipTypeService.class)),
            new ai.intellistream.datahub.api.services.node.NodeUpdateService(
                    nodeRepository, mock(DataSetRepository.class), dataSecurity,
                    mock(LabelService.class), mock(NodeService.class), mock(PolicyEnforcement.class)),
            mock(ai.intellistream.datahub.api.policy.NamingPolicyResolver.class));

    private static NodeEntity node(long id, String externalId) {
        AssetEntity entity = new AssetEntity();
        entity.setId(id);
        entity.setExternalId(externalId);
        entity.setName(externalId);
        return entity;
    }

    @Test
    @DisplayName("an externalId-only request resolves and traverses")
    void resolvesExternalId() {
        NodeEntity pump = node(42L, "21-p-101a");
        when(nodeRepository.findByExternalId("21-p-101a")).thenReturn(pump);
        when(nodeRepository.findById(42L)).thenReturn(Optional.of(pump));
        when(neo4JService.fetchNearestNodesByEndLabel(eq(42L), anyList(), any(), any(), anyList()))
                .thenReturn(new ResourceNetwork(Set.of(), Set.of(), Set.of()));

        FetchNearestResourcesForm form = new FetchNearestResourcesForm();
        form.setExternalId("21-p-101a");
        form.setEndLabels(List.of("TIMESERIES"));

        service.fetchNearestRelatedResources(form);

        verify(neo4JService).fetchNearestNodesByEndLabel(eq(42L), eq(List.of("TIMESERIES")),
                eq(10), any(), anyList());
        verify(dataSecurity).assertCanRead(pump);
    }

    @Test
    @DisplayName("an unknown externalId is a 404, not a 500")
    void unknownExternalIdIsNotFound() {
        when(nodeRepository.findByExternalId("does-not-exist")).thenReturn(null);

        FetchNearestResourcesForm form = new FetchNearestResourcesForm();
        form.setExternalId("does-not-exist");
        form.setEndLabels(List.of("TIMESERIES"));

        assertThatThrownBy(() -> service.fetchNearestRelatedResources(form))
                .isInstanceOf(ObjectNotFoundException.class)
                .hasMessageContaining("does-not-exist");
    }

    @Test
    @DisplayName("a numeric id still short-circuits the lookup")
    void numericIdSkipsResolution() {
        NodeEntity pump = node(42L, "21-p-101a");
        when(nodeRepository.findById(42L)).thenReturn(Optional.of(pump));
        when(neo4JService.fetchNearestNodesByEndLabel(eq(42L), anyList(), any(), any(), anyList()))
                .thenReturn(new ResourceNetwork(Set.of(), Set.of(), Set.of()));

        FetchNearestResourcesForm form = new FetchNearestResourcesForm();
        form.setId(42L);
        form.setEndLabels(List.of("TIMESERIES"));

        service.fetchNearestRelatedResources(form);

        verify(nodeRepository, org.mockito.Mockito.never()).findByExternalId(any());
    }
}
