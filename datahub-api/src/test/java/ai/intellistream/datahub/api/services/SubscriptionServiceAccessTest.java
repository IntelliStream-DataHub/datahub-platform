// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.services;

import ai.intellistream.datahub.api.controllers.errors.BadRequestException;
import ai.intellistream.datahub.api.datasecurity.DataSecurity;
import ai.intellistream.datahub.api.datasecurity.DatasetPermissions;
import ai.intellistream.datahub.api.datasecurity.TestDataSecurity;
import ai.intellistream.datahub.api.responses.DataWrapper;
import ai.intellistream.datahub.jpa.domains.DatasetEntity;
import ai.intellistream.datahub.jpa.domains.TimeseriesEntity;
import ai.intellistream.datahub.models.IdCollection;
import ai.intellistream.datahub.pulsar.TopicNames;
import ai.intellistream.datahub.repositories.node.TimeseriesRepository;
import ai.intellistream.datahub.repositories.subscription.SubscriptionRepository;
import ai.intellistream.datahub.subscription.Subscription;
import org.apache.pulsar.client.admin.PulsarAdmin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Dataset-ACL coverage for {@link SubscriptionService#create}: a caller may only create a
 * subscription over timeseries whose dataset they can read, and a denied create must never persist
 * the row or provision the Pulsar subscription.
 */
class SubscriptionServiceAccessTest {

    private final SubscriptionRepository subscriptionRepository = mock(SubscriptionRepository.class);
    private final TimeseriesRepository timeseriesRepository = mock(TimeseriesRepository.class);
    private final PulsarAdmin pulsarAdmin = mock(PulsarAdmin.class);
    private final TopicNames topicNames = mock(TopicNames.class);
    private final ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
    // Grants are stated as dataset ids rather than as realm-role strings: per-dataset grants now
    // come from Keycloak organization groups resolved through Valkey and Postgres, and standing
    // that machinery up here would only re-derive the ids the test already knows.
    private DatasetPermissions permissions = DatasetPermissions.none();
    private final DataSecurity dataSecurity = TestDataSecurity.backedBy(() -> permissions);

    private final SubscriptionService service = new SubscriptionService(
            subscriptionRepository, timeseriesRepository, pulsarAdmin, topicNames, eventPublisher, dataSecurity);

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    /** The caller may read these datasets. */
    private void canRead(Long... datasetIds) {
        permissions = DatasetPermissions.of(false, false, Set.of(datasetIds), Set.of());
    }

    /** The caller holds both all-datasets wildcard grants. */
    private void withAllDatasetsGrant() {
        permissions = DatasetPermissions.allDatasets();
    }

    private static void authWith(String... roles) {
        List<GrantedAuthority> authorities = Arrays.stream(roles)
                .map(r -> (GrantedAuthority) new SimpleGrantedAuthority(r))
                .toList();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("user", "pw", authorities));
    }

    private TimeseriesEntity timeseriesInDataset(long tsId, String externalId, long datasetId) {
        DatasetEntity ds = mock(DatasetEntity.class);
        when(ds.getId()).thenReturn(datasetId);
        TimeseriesEntity ts = mock(TimeseriesEntity.class);
        when(ts.getId()).thenReturn(tsId);
        when(ts.getExternalId()).thenReturn(externalId);
        when(ts.getDataSet()).thenReturn(ds);
        return ts;
    }

    private static DataWrapper<Subscription> createRequest(String subExternalId, String... timeseriesExternalIds) {
        Subscription sub = new Subscription();
        sub.setExternalId(subExternalId);
        sub.setName(subExternalId);
        sub.setTimeseries(Arrays.stream(timeseriesExternalIds).map(IdCollection::createFromExternalId).toList());
        DataWrapper<Subscription> req = new DataWrapper<>();
        req.getItems().add(sub);
        return req;
    }

    @Test
    void deniesCreateWhenCallerCannotReadTheTimeseriesDataset() {
        // Caller may read dataset 7; the requested timeseries lives in dataset 9.
        canRead(7L);
        TimeseriesEntity ts = timeseriesInDataset(1L, "secret_ts", 9L);
        when(timeseriesRepository.findAllByIdOrExternalId(any(), any())).thenReturn(List.of(ts));

        assertThatThrownBy(() -> service.create(createRequest("sub-secret", "secret_ts")))
                .isInstanceOf(AccessDeniedException.class);

        // The denied create must not have persisted the row or touched Pulsar.
        verify(subscriptionRepository, never()).save(any());
        verify(subscriptionRepository, never()).existsByExternalIdHash(anyLong());
        verifyNoInteractions(pulsarAdmin);
    }

    @Test
    void deniesCreateWhenAnyOneOfSeveralTimeseriesIsUnreadable() {
        canRead(7L);
        TimeseriesEntity readable = timeseriesInDataset(1L, "ok_ts", 7L);
        TimeseriesEntity forbidden = timeseriesInDataset(2L, "secret_ts", 9L);
        when(timeseriesRepository.findAllByIdOrExternalId(any(), any())).thenReturn(List.of(readable, forbidden));

        assertThatThrownBy(() -> service.create(createRequest("sub-mixed", "ok_ts", "secret_ts")))
                .isInstanceOf(AccessDeniedException.class);

        verify(subscriptionRepository, never()).save(any());
        verifyNoInteractions(pulsarAdmin);
    }

    @Test
    void allowsCreatePastTheAclWhenCallerCanReadTheDataset() {
        // Caller may read dataset 9. The ACL check passes; we stub a duplicate so create fails with a
        // BadRequestException *after* the ACL — proving a readable dataset is not over-blocked.
        canRead(9L);
        TimeseriesEntity ts = timeseriesInDataset(1L, "ok_ts", 9L);
        when(timeseriesRepository.findAllByIdOrExternalId(any(), any())).thenReturn(List.of(ts));
        when(subscriptionRepository.existsByExternalIdHash(anyLong())).thenReturn(true);

        assertThatThrownBy(() -> service.create(createRequest("sub-ok", "ok_ts")))
                .isInstanceOf(BadRequestException.class);

        // Got past the ACL (reached the duplicate check) but never persisted or provisioned.
        verify(subscriptionRepository).existsByExternalIdHash(anyLong());
        verify(subscriptionRepository, never()).save(any());
        verifyNoInteractions(pulsarAdmin);
    }

    @Test
    void allowsCreatePastTheAclForReadAllCaller() {
        withAllDatasetsGrant();
        TimeseriesEntity ts = timeseriesInDataset(1L, "any_ts", 123L);
        when(timeseriesRepository.findAllByIdOrExternalId(any(), any())).thenReturn(List.of(ts));
        when(subscriptionRepository.existsByExternalIdHash(anyLong())).thenReturn(true);

        assertThatThrownBy(() -> service.create(createRequest("sub-all", "any_ts")))
                .isInstanceOf(BadRequestException.class);

        verify(subscriptionRepository).existsByExternalIdHash(anyLong());
        verify(subscriptionRepository, never()).save(any());
    }
}
