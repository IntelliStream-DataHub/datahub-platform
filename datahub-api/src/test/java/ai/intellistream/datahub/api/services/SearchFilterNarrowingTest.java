// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.services;

import ai.intellistream.datahub.api.datasecurity.DataSecurity;
import ai.intellistream.datahub.api.datasecurity.DatasetClosureService;
import ai.intellistream.datahub.api.policy.PolicyEnforcement;
import ai.intellistream.datahub.clickhouse.ClickHouseEventService;
import ai.intellistream.datahub.jpa.domains.NodeEntity;
import ai.intellistream.datahub.jpa.domains.NodeType;
import ai.intellistream.datahub.models.IdCollection;
import ai.intellistream.datahub.models.SearchForm;
import ai.intellistream.datahub.models.datafilters.DataSetFilter;
import ai.intellistream.datahub.models.datafilters.ResourceFilter;
import ai.intellistream.datahub.models.events.EventFilter;
import ai.intellistream.datahub.repositories.node.*;
import ai.intellistream.datahub.repositories.subscription.SubscriptionRepository;
import ai.intellistream.datahub.services.LabelService;
import ai.intellistream.datahub.services.Neo4JService;
import ai.intellistream.datahub.services.NodeService;
import ai.intellistream.datahub.services.RelationshipTypeService;
import ai.intellistream.datahub.models.SearchBody;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Root;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The four searches are each their sibling {@code filter} query plus one phrase predicate, in a
 * single query.
 *
 * <p>Two defects sit behind these tests. The {@code filter} on the resource, data set and event
 * searches was declared, validated, published in the OpenAPI schema and then never read: thirty
 * request fields accepted and silently dropped, which is worse than rejecting them, because a
 * caller asking for "pumps, but only alarms" got every pump back and no sign that half the request
 * was ignored.
 *
 * <p>Then the first fix ran the phrase as its own query up to a 10 000-row candidate ceiling and
 * narrowed those ids with a second query. That cost a round trip, hid the conjunction from the
 * planner, and past the ceiling was simply wrong: with no ordering on the candidate query, an
 * arbitrary 10 000 rows got narrowed, so a row matching both phrase and filter could go missing.
 * These tests pin the caller's own {@code limit} reaching the query, which is what having one
 * query means.
 *
 * <p>That the phrase and the filter actually compose into working SQL against the GIN index is
 * {@code NodeSearchCompositionIT}'s job; it needs a real Postgres, which these do not.
 */
class SearchFilterNarrowingTest {

    private static SearchForm phrase(String query) {
        SearchForm form = new SearchForm();
        form.setQuery(query);
        return form;
    }

    @Nested
    class Datasets {

        private final DataSetRepository dataSetRepository = mock(DataSetRepository.class);
        private final DataSetService service = new DataSetService(
                mock(NodeRepository.class), mock(ResourceService.class), dataSetRepository);

        private SearchBody<DataSetFilter> searchFor(String query, DataSetFilter filter) {
            SearchBody<DataSetFilter> form = new SearchBody<>();
            form.setSearch(phrase(query));
            form.setFilter(filter);
            return form;
        }

        @Test
        void thePhraseAndTheFilterGoToTheSameQuery() {
            DataSetFilter filter = new DataSetFilter();
            filter.setLabels(List.of("SAP"));
            when(dataSetRepository.search(anyString(), any(), anyInt(), any(), any()))
                    .thenReturn(List.of());

            SearchBody<DataSetFilter> form = searchFor("work order", filter);
            form.setLimit(25);
            service.search(form);

            // One call, carrying both. The filter used to be dropped entirely, and the phrase used
            // to run on its own first.
            verify(dataSetRepository).search(eq("work order"), eq(filter), eq(25), any(), isNull());
        }

        @Test
        void aSearchWithNoFilterPassesNullRatherThanAnEmptyFilter() {
            when(dataSetRepository.search(anyString(), any(), anyInt(), any(), any()))
                    .thenReturn(List.of());

            service.search(searchFor("work order", null));

            // Null is "narrow nothing"; the repository treats it as an empty filter.
            verify(dataSetRepository).search(eq("work order"), isNull(), eq(100), any(), isNull());
        }

        @Test
        void theCallersLimitReachesTheQueryRatherThanACandidateCeiling() {
            DataSetFilter filter = new DataSetFilter();
            filter.setLabels(List.of("SAP"));
            when(dataSetRepository.search(anyString(), any(), anyInt(), any(), any()))
                    .thenReturn(List.of());

            SearchBody<DataSetFilter> form = searchFor("work order", filter);
            form.setLimit(10);
            service.search(form);

            // A filtered search used to raise this to 10 000 so it had candidates left to narrow.
            verify(dataSetRepository).search(anyString(), any(), eq(10), any(), isNull());
        }
    }

    @Nested
    class Resources {

        private final EntityManager entityManager = mock(EntityManager.class);
        private final DataSecurity dataSecurity = mock(DataSecurity.class);
        private final DatasetClosureService closure = mock(DatasetClosureService.class);
        private final ResourceService service = new ResourceService(
                entityManager, mock(NodeRepository.class), mock(NodeService.class),
                mock(LabelService.class), mock(EdgeRepository.class),
                mock(RelationshipTypeRepository.class), mock(RelationshipTypeService.class),
                mock(ApplicationEventPublisher.class), mock(Neo4JService.class),
                mock(DataSetRepository.class), dataSecurity,
                mock(SubscriptionRepository.class), mock(Validator.class),
                mock(PolicyEnforcement.class), closure,
                new ai.intellistream.datahub.transformers.NodeReadMapper());

        private SearchBody<ResourceFilter> searchFor(String query, ResourceFilter filter) {
            SearchBody<ResourceFilter> form = new SearchBody<>();
            form.setSearch(phrase(query));
            form.setFilter(filter);
            return form;
        }

        /** Enough of the Criteria chain to let the query be built and executed. */
        @SuppressWarnings("unchecked")
        private TypedQuery<NodeEntity> criteriaChainIsStubbed() {
            CriteriaBuilder cb = mock(CriteriaBuilder.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
            CriteriaQuery<NodeEntity> query = mock(CriteriaQuery.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
            TypedQuery<NodeEntity> typed = mock(TypedQuery.class);
            when(entityManager.getCriteriaBuilder()).thenReturn(cb);
            when(cb.createQuery(NodeEntity.class)).thenReturn(query);
            when(query.from(NodeEntity.class)).thenReturn(mock(Root.class, org.mockito.Mockito.RETURNS_DEEP_STUBS));
            when(entityManager.createQuery(any(CriteriaQuery.class))).thenReturn(typed);
            when(typed.getResultList()).thenReturn(List.of());
            return typed;
        }

        @Test
        void theCallersLimitReachesTheQueryRatherThanACandidateCeiling() {
            when(dataSecurity.hasReadAccessToEverything()).thenReturn(true);
            TypedQuery<NodeEntity> typed = criteriaChainIsStubbed();

            SearchBody<ResourceFilter> form = searchFor("pump", null);
            form.setLimit(50);
            service.search(form);

            // Not 10 000, and not 50-per-node-type across five queries: one query, one cap.
            verify(typed).setMaxResults(50);
            verify(entityManager).createQuery(any(CriteriaQuery.class));
        }

        @Test
        void aNodeTypeListOfOnlyUnknownNamesMatchesNothing() {
            ResourceFilter filter = new ResourceFilter();
            filter.setNodeType(List.of("widget"));

            var result = service.search(searchFor("pump", filter));

            assertThat(result.getItems()).isEmpty();
            // They asked to be narrowed to those types; widening back to all of them would be the
            // opposite of the request, so nothing is queried at all.
            verify(entityManager, never()).createQuery(any(CriteriaQuery.class));
        }

        @Test
        void aKnownNodeTypeResolvesToItsDiscriminator() {
            when(dataSecurity.hasReadAccessToEverything()).thenReturn(true);
            criteriaChainIsStubbed();

            ResourceFilter filter = new ResourceFilter();
            filter.setNodeType(List.of("timeseries", "policy"));
            service.search(searchFor("pump", filter));

            // Policies are searchable: the five per-type queries this replaced never asked the
            // policy repository, so a type /resources/filter returns was unreachable from search.
            assertThat(NodeType.idsForNames(filter.getNodeType()))
                    .containsExactlyInAnyOrder(NodeType.TIMESERIES, NodeType.POLICY);
            verify(entityManager).createQuery(any(CriteriaQuery.class));
        }

        @Test
        void dataSetIdIsExpandedThroughTheHierarchyAndIntersectedWithWhatTheCallerMayRead() {
            when(dataSecurity.hasReadAccessToEverything()).thenReturn(false);
            when(dataSecurity.readableDataSetIds()).thenReturn(Set.of(10L, 11L));
            when(closure.closureOfReferences(any())).thenReturn(Set.of(10L, 99L));
            criteriaChainIsStubbed();

            ResourceFilter filter = new ResourceFilter();
            filter.setDataSetId(List.of(IdCollection.createFromId(10L)));
            service.search(searchFor("pump", filter));

            // 99 is in the closure but not readable, so it drops out: the filter can only ever
            // narrow what the caller was already allowed to see.
            verify(closure).closureOfReferences(List.of(IdCollection.createFromId(10L)));
            verify(entityManager).createQuery(any(CriteriaQuery.class));
        }

        @Test
        void aDataSetScopeThatResolvesToNothingReadableMatchesNothing() {
            when(dataSecurity.hasReadAccessToEverything()).thenReturn(false);
            when(dataSecurity.readableDataSetIds()).thenReturn(Set.of(10L));
            when(closure.closureOfReferences(any())).thenReturn(Set.of(99L)); // disjoint

            ResourceFilter filter = new ResourceFilter();
            filter.setDataSetId(List.of(IdCollection.createFromId(99L)));
            var result = service.search(searchFor("pump", filter));

            assertThat(result.getItems()).isEmpty();
            // Dropping the predicate instead would widen the search to everything they can read.
            verify(entityManager, never()).createQuery(any(CriteriaQuery.class));
        }

        @Test
        void aCallerWithNoReadableDataSetsGetsNothingWithoutQuerying() {
            when(dataSecurity.hasReadAccessToEverything()).thenReturn(false);
            when(dataSecurity.readableDataSetIds()).thenReturn(Set.of());

            var result = service.search(searchFor("pump", null));

            assertThat(result.getItems()).isEmpty();
            verify(entityManager, never()).createQuery(any(CriteriaQuery.class));
        }
    }

    @Nested
    @ExtendWith(MockitoExtension.class)
    class Events {

        @Mock private ClickHouseEventService clickHouse;
        @Mock private DataSecurity dataSecurity;
        @Mock private DatasetClosureService closure;
        @Mock private NodeRepository nodeRepository;
        @Mock private ai.intellistream.datahub.services.KVRocksService kvRocksService;
        @Mock private Validator validator;
        @Mock private ApplicationEventPublisher applicationEventPublisher;
        @Mock private org.apache.pulsar.client.api.Producer<ai.intellistream.datahub.pulsar.EventCudMessage> producer;
        @Mock private ai.intellistream.datahub.repositories.event.EventDimensionRepository eventDimensionRepository;
        @Mock private DataSetRepository dataSetRepository;

        @InjectMocks private EventService service;

        private SearchBody<EventFilter> searchFor(String query, EventFilter filter) {
            SearchBody<EventFilter> form = new SearchBody<>();
            form.setSearch(phrase(query));
            form.setFilter(filter);
            return form;
        }

        /**
         * Events were always one query: the phrase, the filter and the ACL are ANDed in ClickHouse,
         * so this search never had a candidate ceiling to remove.
         */
        @Test
        void theFilterReachesTheQueryRatherThanBeingDropped() {
            when(dataSecurity.hasReadAccessToEverything()).thenReturn(true);
            when(clickHouse.search(any(), anyInt(), any(), any())).thenReturn(List.of());

            EventFilter filter = new EventFilter();
            filter.setType(List.of("Alarm"));
            service.search(searchFor("bearing", filter));

            verify(clickHouse).search(eq("bearing"), eq(100), isNull(), eq(filter));
        }

        @Test
        void dataSetIdIsExpandedToItsHierarchyTheWayTheFilterEndpointExpandsIt() {
            when(dataSecurity.hasReadAccessToEverything()).thenReturn(true);
            when(clickHouse.search(any(), anyInt(), any(), any())).thenReturn(List.of());
            when(closure.closureOfReferences(any())).thenReturn(Set.of(10L, 11L));

            EventFilter filter = new EventFilter();
            filter.setDataSetId(List.of(IdCollection.createFromId(10L)));
            service.search(searchFor("bearing", filter));

            // Rewritten in place to the closure: naming a parent data set has to cover its
            // children here exactly as it does on POST /events/filter.
            assertThat(filter.getDataSetId())
                    .extracting(IdCollection::getId)
                    .containsExactlyInAnyOrder(10L, 11L);
        }

        @Test
        void noFilterMeansNoNarrowing() {
            when(dataSecurity.hasReadAccessToEverything()).thenReturn(true);
            when(clickHouse.search(any(), anyInt(), any(), any())).thenReturn(List.of());

            service.search(searchFor("bearing", null));

            verify(clickHouse).search(eq("bearing"), eq(100), isNull(), isNull());
        }
    }
}
