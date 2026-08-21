// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.services;

import ai.intellistream.datahub.api.datasecurity.DataSecurity;
import ai.intellistream.datahub.api.responses.DataWrapper;
import ai.intellistream.datahub.api.responses.DataWrapperBin;
import ai.intellistream.datahub.api.responses.DatapointString;
import ai.intellistream.datahub.api.responses.DatapointsCollection;
import ai.intellistream.datahub.jpa.domains.TimeseriesEntity;
import ai.intellistream.datahub.jpa.domains.TimeseriesValueType;
import ai.intellistream.datahub.repositories.node.TimeseriesRepository;
import ai.intellistream.datahub.tenant.TenantContext;
import ai.intellistream.datahub.services.ValkeyService;
import org.apache.pulsar.client.api.Producer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The observable contract of {@link TimeseriesService#insertDatapoints}.
 *
 * <p>This exists because the method had no direct coverage: the only test that touched it,
 * {@code TimeseriesControllerTest}, mocks the whole method out, so nothing would have caught a
 * regression inside it.
 *
 * <p>The case that matters most here is {@link #publishIsSynchronousSoA2xxMeansPulsarHasTheData()}.
 * Pulsar is the source of truth for datapoints, with no Postgres copy to reconcile from, so the
 * send must complete before the method returns. Anything that moves the publish off the calling
 * thread turns a crash into silent data loss after the caller has already been told the write
 * succeeded. See SCALABILITY.md.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TimeseriesServiceInsertDatapointsTest {

    private static final String TENANT = "acme";

    @Mock private TimeseriesRepository timeseriesRepository;
    @Mock private DataSecurity dataSecurity;
    @Mock private ValkeyService valkeyService;
    @Mock private Producer<DataWrapperBin> allDatapointProducer;
    @Mock private LiveIngestCounter datapointIngestCounter;
    @Mock private TransactionTemplate transactionTemplate;

    @InjectMocks private TimeseriesService timeseriesService;

    @BeforeEach
    void setTenant() {
        TenantContext.setTenantId(TENANT);
    }

    /**
     * Run the read phase inline. insertDatapoints scopes its database reads with this template
     * rather than annotating the whole method, so that the Valkey and Pulsar calls happen after the
     * transaction has ended and no pooled connection is held across them.
     */
    @BeforeEach
    void runReadPhaseInline() {
        when(transactionTemplate.execute(any()))
                .thenAnswer(inv -> inv.getArgument(0, TransactionCallback.class).doInTransaction(null));
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    // ---- fixtures ---------------------------------------------------------

    /**
     * The value type carries both id and name on purpose. The id drives validation in
     * insertDatapoints, but addData propagates {@code getValueType().getName()} onto the outgoing
     * collection, and that name is what the binary conversion resolves the type from. A fixture
     * with only the id silently degrades to the FLOAT32 default downstream.
     */
    private static TimeseriesEntity timeseries(long id, String externalId, String valueTypeName) {
        TimeseriesEntity ts = new TimeseriesEntity();
        ts.setId(id);
        ts.setExternalId(externalId);
        ts.setValueType(new TimeseriesValueType(
                TimeseriesValueType.getValueTypeId(valueTypeName), valueTypeName));
        return ts;
    }

    private static DatapointString point(String isoTimestamp, String value) {
        DatapointString dp = new DatapointString();
        dp.setTimestamp(isoTimestamp);
        dp.setValue(value);
        return dp;
    }

    private static DatapointsCollection collection(String externalId, DatapointString... points) {
        DatapointsCollection c = new DatapointsCollection();
        c.setExternalId(externalId);
        c.setDatapoints(List.of(points));
        return c;
    }

    private static DataWrapper<DatapointsCollection> request(DatapointsCollection... collections) {
        DataWrapper<DatapointsCollection> w = new DataWrapper<>();
        w.getItems().addAll(List.of(collections));
        return w;
    }

    /** Resolve this external id to a timeseries of the given value type. */
    private void known(String externalId, long id, String valueTypeName) {
        when(timeseriesRepository.findByIdOrExternalId(null, externalId))
                .thenReturn(Optional.of(timeseries(id, externalId, valueTypeName)));
    }

    // ---- tests ------------------------------------------------------------

    @Test
    @DisplayName("A 2xx means Pulsar already has the data: the send completes before returning")
    void publishIsSynchronousSoA2xxMeansPulsarHasTheData() throws Exception {
        known("pump-1", 1L, "FLOAT");

        timeseriesService.insertDatapoints(request(
                collection("pump-1", point("2026-08-21T10:00:00Z", "1.5"))));

        // Blocking send, on the calling thread. sendAsync would return before the broker has
        // acknowledged, which is the regression this test exists to prevent.
        verify(allDatapointProducer).send(any(DataWrapperBin.class));
        verify(allDatapointProducer, never()).sendAsync(any(DataWrapperBin.class));
    }

    @Test
    @DisplayName("Each collection is published as one message carrying the tenant id")
    void oneMessagePerCollection() throws Exception {
        known("pump-1", 1L, "FLOAT");
        known("pump-2", 2L, "BIGINT");

        timeseriesService.insertDatapoints(request(
                collection("pump-1", point("2026-08-21T10:00:00Z", "1.5")),
                collection("pump-2", point("2026-08-21T10:00:01Z", "42"))));

        ArgumentCaptor<DataWrapperBin> sent = ArgumentCaptor.forClass(DataWrapperBin.class);
        verify(allDatapointProducer, times(2)).send(sent.capture());
        assertTrue(sent.getAllValues().stream().allMatch(m -> TENANT.equals(m.getTenantId())),
                "every published message should carry the tenant it was ingested for");
    }

    @Test
    @DisplayName("Ingest counter records the datapoint count, not the message count")
    void counterRecordsDatapointsNotMessages() throws Exception {
        known("pump-1", 1L, "FLOAT");

        timeseriesService.insertDatapoints(request(
                collection("pump-1",
                        point("2026-08-21T10:00:00Z", "1.0"),
                        point("2026-08-21T10:00:01Z", "2.0"),
                        point("2026-08-21T10:00:02Z", "3.0"))));

        verify(datapointIngestCounter).recordIngested(TENANT, 3L);
    }

    @Test
    @DisplayName("An unknown timeseries is reported as 404 and does not stop the other collections")
    void unknownTimeseriesIsReportedAndTheRestStillPublish() throws Exception {
        when(timeseriesRepository.findByIdOrExternalId(null, "missing")).thenReturn(Optional.empty());
        known("pump-1", 1L, "FLOAT");

        DataWrapper<?> response = timeseriesService.insertDatapoints(request(
                collection("missing", point("2026-08-21T10:00:00Z", "1.0")),
                collection("pump-1", point("2026-08-21T10:00:00Z", "1.0"))));

        assertEquals(1, response.getItems().size(), "the miss should be reported");
        // The surviving collection is still published: one bad id does not fail the batch.
        verify(allDatapointProducer, times(1)).send(any(DataWrapperBin.class));
    }

    @Test
    @DisplayName("A collection with no datapoints publishes nothing")
    void emptyCollectionPublishesNothing() throws Exception {
        known("pump-1", 1L, "FLOAT");

        timeseriesService.insertDatapoints(request(collection("pump-1")));

        verify(allDatapointProducer, never()).send(any(DataWrapperBin.class));
        verify(datapointIngestCounter, never()).recordIngested(anyString(), anyLong());
    }

    @Test
    @DisplayName("Write permission is checked for every resolved timeseries")
    void writePermissionIsCheckedPerTimeseries() throws Exception {
        known("pump-1", 1L, "FLOAT");
        known("pump-2", 2L, "FLOAT");

        timeseriesService.insertDatapoints(request(
                collection("pump-1", point("2026-08-21T10:00:00Z", "1.0")),
                collection("pump-2", point("2026-08-21T10:00:00Z", "2.0"))));

        verify(dataSecurity, times(2)).assertCanWrite(any(TimeseriesEntity.class));
    }

    @Test
    @DisplayName("A value that does not parse for the declared type is rejected, and nothing is sent")
    void valueThatDoesNotMatchTheTypeIsRejected() throws Exception {
        known("counter-1", 1L, "BIGINT");

        assertThrows(RuntimeException.class, () -> timeseriesService.insertDatapoints(request(
                collection("counter-1", point("2026-08-21T10:00:00Z", "not-a-number")))));

        verify(allDatapointProducer, never()).send(any(DataWrapperBin.class));
    }

    @Test
    @DisplayName("MIXED accepts text as well as numbers")
    void mixedAcceptsText() throws Exception {
        known("mixed-1", 1L, "MIXED");

        timeseriesService.insertDatapoints(request(
                collection("mixed-1", point("2026-08-21T10:00:00Z", "running"))));

        verify(allDatapointProducer).send(any(DataWrapperBin.class));
    }

    @Test
    @DisplayName("The newest datapoint by timestamp reaches the latest-value cache, whatever the order")
    void latestValueCacheGetsTheNewestPointRegardlessOfOrder() throws Exception {
        known("pump-1", 1L, "FLOAT");
        when(valkeyService.fetchLatestDatapoint("pump-1")).thenReturn(null);

        timeseriesService.insertDatapoints(request(
                collection("pump-1",
                        point("2026-08-21T10:00:01Z", "2.0"),
                        point("2026-08-21T10:00:02Z", "3.0"),
                        point("2026-08-21T10:00:00Z", "1.0"))));

        ArgumentCaptor<DatapointString> cached = ArgumentCaptor.forClass(DatapointString.class);
        verify(valkeyService).setLatestDatapoint(eq("pump-1"), cached.capture());
        assertEquals("2026-08-21T10:00:02Z", cached.getValue().getTimestamp());
    }

    @Test
    @DisplayName("A bad value anywhere in the request means nothing at all is published")
    void validationCoversTheWholeRequestBeforeAnythingIsSent() throws Exception {
        known("pump-1", 1L, "FLOAT");
        known("counter-1", 2L, "BIGINT");

        // The good collection comes first, so under the old per-collection publish it was already
        // in Pulsar by the time the second one threw, and a client retry would double-insert it.
        assertThrows(RuntimeException.class, () -> timeseriesService.insertDatapoints(request(
                collection("pump-1", point("2026-08-21T10:00:00Z", "1.0")),
                collection("counter-1", point("2026-08-21T10:00:00Z", "not-a-number")))));

        verify(allDatapointProducer, never()).send(any(DataWrapperBin.class));
    }

    @Test
    @DisplayName("A collection with no datapoints leaves the latest-value cache alone")
    void emptyCollectionDoesNotTouchTheLatestValueCache() throws Exception {
        known("pump-1", 1L, "FLOAT");

        timeseriesService.insertDatapoints(request(collection("pump-1")));

        // It used to be called with a null datapoint here, which either wrote a null or threw a
        // NullPointerException depending on whether the key already had a value.
        verify(valkeyService, never()).setLatestDatapoint(anyString(), any(DatapointString.class));
    }
}
