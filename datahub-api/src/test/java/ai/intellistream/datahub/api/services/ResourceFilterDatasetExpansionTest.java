// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.services;

import ai.intellistream.datahub.api.messaging.outbox.GraphOutbox;
import ai.intellistream.datahub.api.datasecurity.DataSecurity;
import ai.intellistream.datahub.api.datasecurity.DatasetClosureService;
import ai.intellistream.datahub.api.policy.PolicyEnforcement;
import ai.intellistream.datahub.jpa.domains.NodeEntity;
import ai.intellistream.datahub.models.IdCollection;
import ai.intellistream.datahub.models.ResourceRetreiver;
import ai.intellistream.datahub.models.datafilters.ResourceFilter;
import ai.intellistream.datahub.repositories.node.*;
import ai.intellistream.datahub.repositories.subscription.SubscriptionRepository;
import ai.intellistream.datahub.services.LabelService;
import ai.intellistream.datahub.services.RelationshipTypeService;
import ai.intellistream.datahub.services.Neo4JService;
import ai.intellistream.datahub.services.NodeService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Root;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code POST /resources/filter} expands each {@code dataSetId} entry to everything beneath it in
 * the {@code BELONGS_TO} hierarchy.
 *
 * <p>It used to match the listed ids exactly, so filtering on a parent returned nothing from its
 * children even though a grant on that parent lets the caller read them — and the same filter
 * against timeseries, which did expand, answered differently. All three filters now agree.
 *
 * <p>The two things worth pinning here are that the requested ids reach the shared closure in one
 * call, and that a set which resolves to nothing narrows to nothing instead of quietly widening to
 * everything the caller can read. The traversal itself is {@code DatasetClosureServiceTest}'s.
 */
class ResourceFilterDatasetExpansionTest {

    private final EntityManager entityManager = mock(EntityManager.class);
    private final DatasetClosureService closure = mock(DatasetClosureService.class);
    private final ResourceService service = new ResourceService(
            entityManager, mock(NodeRepository.class), mock(NodeService.class), mock(EdgeRepository.class),
            mock(RelationshipTypeRepository.class), mock(RelationshipTypeService.class),
            mock(ApplicationEventPublisher.class), mock(GraphOutbox.class), mock(Neo4JService.class),
            mock(DataSecurity.class),
            mock(SubscriptionRepository.class), mock(Validator.class),
            mock(PolicyEnforcement.class), closure,
            new ai.intellistream.datahub.api.edge.EdgeMapper(mock(NodeRepository.class), mock(RelationshipTypeRepository.class), mock(RelationshipTypeService.class)),
            new ai.intellistream.datahub.api.services.node.NodeUpdateService(
                    mock(NodeRepository.class), mock(DataSecurity.class),
                    mock(NodeService.class), mock(PolicyEnforcement.class)));

    /** Enough of the Criteria chain to reach the dataSetIds branch; nothing beyond it runs. */
    @SuppressWarnings("unchecked")
    private void criteriaChainIsStubbed() {
        CriteriaBuilder cb = mock(CriteriaBuilder.class);
        CriteriaQuery<NodeEntity> query = mock(CriteriaQuery.class);
        when(entityManager.getCriteriaBuilder()).thenReturn(cb);
        when(cb.createQuery(NodeEntity.class)).thenReturn(query);
        when(query.from(NodeEntity.class)).thenReturn(mock(Root.class));
    }

    private static ResourceRetreiver filteringOn(Long... dataSetIds) {
        var filter = new ResourceFilter();
        filter.setDataSetId(List.of(dataSetIds).stream().map(IdCollection::createFromId).toList());
        var retriever = new ResourceRetreiver();
        retriever.setFilter(filter);
        return retriever;
    }

    @Test
    void aDataSetSetThatResolvesToNothingMatchesNothing() {
        criteriaChainIsStubbed();
        when(closure.closureOfReferences(any())).thenReturn(Set.of());

        var result = service.filter(filteringOn(10L, 20L));

        // Both requested data sets go to the closure in a single call — the recursive query already
        // takes a set of roots, so expanding one at a time would be N queries for no reason.
        verify(closure).closureOfReferences(List.of(
                IdCollection.createFromId(10L), IdCollection.createFromId(20L)));

        // And the empty result narrows to nothing without querying. Dropping the predicate instead
        // would widen the filter to every resource the caller can read — the opposite of the ask.
        assertThat(result.getItems()).isEmpty();
        verify(entityManager, never()).createQuery(any(CriteriaQuery.class));
    }
}
