// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.services;

import ai.intellistream.datahub.api.datasecurity.DataSecurity;
import ai.intellistream.datahub.api.datasecurity.DatasetPermissions;
import ai.intellistream.datahub.api.datasecurity.TestDataSecurity;
import ai.intellistream.datahub.api.responses.DataWrapper;
import ai.intellistream.datahub.jpa.domains.SubscriptionEntity;
import ai.intellistream.datahub.models.DataSort;
import ai.intellistream.datahub.models.datafilters.FilterDefaults;
import ai.intellistream.datahub.models.paging.MalformedCursorException;
import ai.intellistream.datahub.models.paging.PageCursor;
import ai.intellistream.datahub.pulsar.TopicNames;
import ai.intellistream.datahub.repositories.node.TimeseriesRepository;
import ai.intellistream.datahub.repositories.subscription.SubscriptionRepository;
import ai.intellistream.datahub.repositories.subscription.SubscriptionSort;
import ai.intellistream.datahub.subscription.Subscription;
import ai.intellistream.datahub.subscription.SubscriptionFilter;
import ai.intellistream.datahub.subscription.SubscriptionRetriever;
import org.apache.pulsar.client.admin.PulsarAdmin;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code POST /subscriptions/filter} behaving like the rest of the filter family.
 *
 * <p>Every case here is something the endpoint got differently while it was {@code /list}: a page
 * size of its own, a sort property that reached Spring Data unchecked, and no cursor at all — so a
 * tenant with more subscriptions than one page could not reach the rest of them.
 */
class SubscriptionServiceFilterTest {

    private final SubscriptionRepository subscriptionRepository = mock(SubscriptionRepository.class);
    private final TimeseriesRepository timeseriesRepository = mock(TimeseriesRepository.class);
    private final PulsarAdmin pulsarAdmin = mock(PulsarAdmin.class);
    private final TopicNames topicNames = mock(TopicNames.class);
    private final ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
    private final DataSecurity dataSecurity = TestDataSecurity.backedBy(DatasetPermissions::allDatasets);

    private final SubscriptionService service = new SubscriptionService(
            subscriptionRepository, timeseriesRepository, pulsarAdmin, topicNames, eventPublisher, dataSecurity);

    @Test
    void anEmptyBodyUsesTheSharedPageSizeAndTheDefaultOrder() {
        returning(List.of());

        service.filter(new SubscriptionRetriever());

        assertThat(limitPassedToRepository()).isEqualTo(FilterDefaults.DEFAULT_LIMIT);
        assertThat(sortPassedToRepository()).isEqualTo(SubscriptionSort.DEFAULT);
        assertThat(cursorPassedToRepository()).isNull();
    }

    /** A null retriever is what {@code @RequestBody(required = false)} hands over for no body at all. */
    @Test
    void noBodyAtAllIsTheSameAsAnEmptyOne() {
        returning(List.of());

        service.filter(null);

        assertThat(limitPassedToRepository()).isEqualTo(FilterDefaults.DEFAULT_LIMIT);
        assertThat(filterPassedToRepository()).isNotNull();
    }

    @Test
    void anUnknownSortPropertyFallsBackToTheDefaultOrderInsteadOfFailing() {
        returning(List.of());
        SubscriptionRetriever request = new SubscriptionRetriever();
        DataSort sort = new DataSort();
        sort.setProperty(List.of("dateCreated")); // the entity attribute, not the wire property
        request.setSort(sort);

        service.filter(request);

        assertThat(sortPassedToRepository()).isEqualTo(SubscriptionSort.DEFAULT);
    }

    @Test
    void aSortablePropertyIsHonouredInBothDirections() {
        returning(List.of());
        SubscriptionRetriever request = new SubscriptionRetriever();
        DataSort sort = new DataSort();
        sort.setProperty(List.of("externalId"));
        sort.setOrder("asc");
        request.setSort(sort);

        service.filter(request);

        assertThat(sortPassedToRepository())
                .isEqualTo(new SubscriptionSort("externalId", "externalId", false));
    }

    @Test
    void aFullPageHandsBackACursorAndAShortOneEndsTheWalk() {
        int limit = 3;
        returning(page(limit));
        SubscriptionRetriever request = new SubscriptionRetriever();
        request.setLimit(limit);

        DataWrapper<Subscription> full = service.filter(request);
        assertThat(full.getNextCursor()).isNotNull();

        returning(page(limit - 1));
        DataWrapper<Subscription> last = service.filter(request);
        assertThat(last.getNextCursor())
                .as("a short page is the end of the results; 'keep going while nextCursor is present' "
                        + "is then the whole client loop")
                .isNull();
    }

    @Test
    void aCursorIsContinuedUnderTheSortThatProducedIt() {
        returning(List.of());
        SubscriptionRetriever request = new SubscriptionRetriever();
        request.setCursor(new PageCursor("createdTime", true, "1754476522104", "42").encode());

        service.filter(request);

        PageCursor passed = cursorPassedToRepository();
        assertThat(passed).isNotNull();
        assertThat(passed.id()).isEqualTo("42");
    }

    @Test
    void aCursorFromADifferentSortIsRejectedRatherThanAnsweredWithAWrongPage() {
        returning(List.of());
        SubscriptionRetriever request = new SubscriptionRetriever();
        request.setCursor(new PageCursor("externalId", false, "fleet_dashboard", "42").encode());
        DataSort sort = new DataSort();
        sort.setProperty(List.of("name"));
        request.setSort(sort);

        assertThatThrownBy(() -> service.filter(request))
                .isInstanceOf(MalformedCursorException.class);
    }

    /** A forged boundary must be a 400, not a {@code NumberFormatException} out of the query builder. */
    @Test
    void aCursorWhoseBoundaryIsNotReadableAsTheColumnIsRejected() {
        returning(List.of());
        SubscriptionRetriever request = new SubscriptionRetriever();
        request.setCursor(new PageCursor("createdTime", true, "not-a-timestamp", "42").encode());

        assertThatThrownBy(() -> service.filter(request))
                .isInstanceOf(MalformedCursorException.class);
    }

    private void returning(List<SubscriptionEntity> entities) {
        when(subscriptionRepository.filter(any(), anyInt(), any(), any())).thenReturn(entities);
    }

    private static List<SubscriptionEntity> page(int size) {
        List<SubscriptionEntity> entities = new ArrayList<>();
        IntStream.range(0, size).forEach(i -> {
            SubscriptionEntity e = new SubscriptionEntity();
            e.setId((long) (i + 1));
            e.setExternalId("sub_" + i);
            e.setName("Sub " + i);
            e.setDateCreated(OffsetDateTime.of(2026, 1, 1, 0, 0, i, 0, ZoneOffset.UTC));
            e.setLastUpdated(e.getDateCreated());
            entities.add(e);
        });
        return entities;
    }

    // The last set of arguments the service handed the repository. Captured together rather than
    // one accessor per argument, so a call is verified once however many of its arguments a test
    // goes on to assert about.
    private final ArgumentCaptor<SubscriptionFilter> filterArg = ArgumentCaptor.forClass(SubscriptionFilter.class);
    private final ArgumentCaptor<Integer> limitArg = ArgumentCaptor.forClass(Integer.class);
    private final ArgumentCaptor<SubscriptionSort> sortArg = ArgumentCaptor.forClass(SubscriptionSort.class);
    private final ArgumentCaptor<PageCursor> cursorArg = ArgumentCaptor.forClass(PageCursor.class);

    private void captureQuery() {
        verify(subscriptionRepository, atLeastOnce())
                .filter(filterArg.capture(), limitArg.capture(), sortArg.capture(), cursorArg.capture());
    }

    private SubscriptionFilter filterPassedToRepository() {
        captureQuery();
        return filterArg.getValue();
    }

    private int limitPassedToRepository() {
        captureQuery();
        return limitArg.getValue();
    }

    private SubscriptionSort sortPassedToRepository() {
        captureQuery();
        return sortArg.getValue();
    }

    private PageCursor cursorPassedToRepository() {
        captureQuery();
        return cursorArg.getValue();
    }
}
