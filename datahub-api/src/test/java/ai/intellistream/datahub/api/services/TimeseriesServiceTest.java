// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.services;

import ai.intellistream.datahub.api.controllers.errors.BadRequestException;
import ai.intellistream.datahub.api.controllers.errors.DuplicateDataException;
import ai.intellistream.datahub.api.datasecurity.DataSecurity;
import ai.intellistream.datahub.api.datasecurity.DatasetClosureService;
import ai.intellistream.datahub.api.messaging.events.DatapointCudPublishEvent;
import ai.intellistream.datahub.api.responses.DataCollectionBin;
import ai.intellistream.datahub.api.responses.DataRetriever;
import ai.intellistream.datahub.api.responses.DataWrapper;
import ai.intellistream.datahub.api.responses.DataWrapperBin;
import ai.intellistream.datahub.jpa.domains.EdgeEntity;
import ai.intellistream.datahub.jpa.domains.TimeseriesEntity;
import ai.intellistream.datahub.jpa.domains.TimeseriesValueType;
import ai.intellistream.datahub.pulsar.EventAction;
import ai.intellistream.datahub.pulsar.EventObject;
import ai.intellistream.datahub.services.ValkeyService;
import ai.intellistream.datahub.tenant.TenantContext;
import ai.intellistream.datahub.jpa.dto.NameAndExternalId;
import ai.intellistream.datahub.models.DeleteDatapoint;
import ai.intellistream.datahub.models.IdCollection;
import ai.intellistream.datahub.models.TimeseriesRetreiver;
import ai.intellistream.datahub.models.SearchForm;
import ai.intellistream.datahub.models.datafilters.TimeseriesFilter;
import ai.intellistream.datahub.repositories.node.DataSetRepository;
import ai.intellistream.datahub.repositories.node.EdgeRepository;
import ai.intellistream.datahub.repositories.node.NodeRepository;
import ai.intellistream.datahub.services.Neo4JService;
import ai.intellistream.datahub.repositories.node.NodeSort;
import ai.intellistream.datahub.repositories.node.TimeseriesRepository;
import ai.intellistream.datahub.timeseries.Timeseries;
import ai.intellistream.datahub.models.SearchBody;
import jakarta.validation.Validator;
import org.apache.pulsar.client.api.Producer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.Collections;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Covers the create-time validation that used to be dead code: a duplicate externalId must surface
 * as a {@link DuplicateDataException} (409) and a non-existent dataSetId as a
 * {@link BadRequestException} (400), rather than a generic 500 from the DB constraint/FK violation.
 * The unique constraint spans the whole node table, so the duplicate check goes through
 * NodeRepository — a collision with ANY node type (resource, dataset, timeseries) must 409.
 */
@ExtendWith(MockitoExtension.class)
class TimeseriesServiceTest {

    @Mock private Validator validator;
    @Mock private DataSecurity dataSecurity;
    @Mock private TimeseriesRepository timeseriesRepository;
    @Mock private DataSetRepository datasetEntityRepository;
    @Mock private NodeRepository nodeRepository;
    @Mock private DatasetClosureService datasetClosureService;
    @Mock private EdgeRepository edgeRepository;
    @Mock private ResourceService resourceService;
    @Mock private ValkeyService valkeyService;
    @Mock private ApplicationEventPublisher applicationEventPublisher;
    @Mock private Producer<DataWrapperBin> allDatapointProducer;
    @Mock private ai.intellistream.datahub.services.NodeService nodeService;
    @Mock private ai.intellistream.datahub.api.policy.PolicyEnforcement policyEnforcement;
    @Mock private ai.intellistream.datahub.api.services.node.NodeUpdateService nodeUpdateService;

    @InjectMocks private TimeseriesService timeseriesService;

    private static DataWrapper<Timeseries> wrap(Timeseries... items) {
        DataWrapper<Timeseries> w = new DataWrapper<>();
        for (Timeseries ts : items) {
            w.getItems().add(ts);
        }
        return w;
    }

    private static NameAndExternalId node(long id, String externalId) {
        NameAndExternalId n = mock(NameAndExternalId.class);
        when(n.getExternalId()).thenReturn(externalId);
        return n;
    }

    @Test
    void save_whenExternalIdAlreadyExists_throwsDuplicateData() throws Exception {
        when(validator.validate(any())).thenReturn(Collections.emptySet());
        NameAndExternalId existing = node(7L, "temperature"); // build BEFORE the outer when()
        when(nodeRepository.findAllByExternalIdHashIn(anyList(), eq(NameAndExternalId.class)))
                .thenReturn(List.of(existing));

        // No dataSetId → dataset validation is skipped, so we reach the duplicate check.
        Timeseries ts = ts("temperature");

        assertThrows(DuplicateDataException.class, () -> timeseriesService.save(wrap(ts)));
    }

    @Test
    void save_whenExternalIdCollidesWithAnotherNodeType_throwsDuplicateData() throws Exception {
        // The constraint is node-wide: an externalId already used by a RESOURCE (or dataset) must
        // 409 too, not pass a timeseries-only check and die on the constraint as a 500.
        when(validator.validate(any())).thenReturn(Collections.emptySet());
        NameAndExternalId resource = node(3L, "pump_42"); // a RESOURCE node; build BEFORE the outer when()
        when(nodeRepository.findAllByExternalIdHashIn(anyList(), eq(NameAndExternalId.class)))
                .thenReturn(List.of(resource));

        Timeseries ts = ts("pump_42");

        assertThrows(DuplicateDataException.class, () -> timeseriesService.save(wrap(ts)));
    }

    @Test
    void save_whenBatchContainsTheSameExternalIdTwice_throwsDuplicateData() throws Exception {
        // Neither row is in the DB yet, so only an intra-batch check catches this before the
        // unique constraint turns it into a 500. Must throw before any repository lookup.
        when(validator.validate(any())).thenReturn(Collections.emptySet());

        Timeseries first = ts("humidity");
        Timeseries second = ts("humidity");

        assertThrows(DuplicateDataException.class, () -> timeseriesService.save(wrap(first, second)));
        verifyNoInteractions(nodeRepository);
    }

    @Test
    void save_whenDataSetDoesNotExist_throwsBadRequest() throws Exception {
        when(validator.validate(any())).thenReturn(Collections.emptySet());
        when(datasetEntityRepository.findAllById(any())).thenReturn(List.of()); // dataset 99 not found

        Timeseries ts = ts("pressure");
        ts.setDataSetId(99L);

        assertThrows(BadRequestException.class, () -> timeseriesService.save(wrap(ts)));
    }

    /** Timeseries with just an externalId set — replaces the removed fluent {@code Timeseries.of(...)}. */
    private static Timeseries ts(String externalId) {
        Timeseries t = new Timeseries();
        t.setExternalId(externalId);
        return t;
    }

    // ---- deleteTimeseries: the data-points must follow the definition ---------------------------

    @Test
    void deleteTimeseries_publishesAFullyOpenPurgeForEveryDeletedTimeseries() throws Exception {
        TenantContext.setTenantId("acme");
        try {
            when(timeseriesRepository.findAllByIdCollection(anyCollection()))
                    .thenReturn(List.of(timeseriesEntity(5L, "sensor_a", "FLOAT"),
                                        timeseriesEntity(6L, "sensor_b", "TEXT")));

            timeseriesService.deleteTimeseries(idCollectionFor("sensor_a", "sensor_b"));

            // The node rows go through the shared pipeline; ClickHouse is not its concern, so the
            // purge has to be emitted here or the data-points would outlive the definition.
            verify(resourceService).delete(any());

            var captor = ArgumentCaptor.forClass(DatapointCudPublishEvent.class);
            verify(applicationEventPublisher).publishEvent(captor.capture());
            DataWrapperBin purge = captor.getValue().message();

            assertEquals(EventObject.DATAPOINTS, purge.getEventObject());
            assertEquals(EventAction.DELETE, purge.getEventAction());
            assertEquals("acme", purge.getTenantId());
            assertEquals(2, purge.getItems().size());

            for (DataCollectionBin item : purge.getItems()) {
                // No window at all — the consumer reads that as "every row for this timeseries".
                assertNull(item.getInclusiveBegin(), "purge must not be bounded at the start");
                assertNull(item.getExclusiveEnd(), "purge must not be bounded at the end");
                // The value type picks the ClickHouse table, so it has to survive onto the message.
                assertNotNull(item.getValueType());
            }
            assertEquals(List.of(5L, 6L), purge.getItems().stream().map(DataCollectionBin::getId).toList());
            assertEquals(List.of("FLOAT", "TEXT"),
                    purge.getItems().stream().map(DataCollectionBin::getValueType).toList());

            // The cached latest datapoint is keyed by externalId and has no TTL, so it must go too —
            // otherwise a timeseries later recreated under the same externalId inherits a dead value.
            verify(valkeyService).deleteLatestDatapoint("sensor_a");
            verify(valkeyService).deleteLatestDatapoint("sensor_b");
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    void deleteTimeseries_whenNothingMatched_purgesNothing() throws Exception {
        when(timeseriesRepository.findAllByIdCollection(anyCollection())).thenReturn(List.of());

        timeseriesService.deleteTimeseries(idCollectionFor("missing"));

        // Deleting an absent timeseries is a no-op 204; it must not wipe anything in ClickHouse.
        verifyNoInteractions(applicationEventPublisher);
        verifyNoInteractions(valkeyService);
        verify(resourceService, never()).delete(any());
    }

    // ---- deleteDatapoints: the window bounds are validated at the API boundary ------------------

    @Test
    void deleteDatapoints_normalisesEpochMillisBoundsToIso() throws Exception {
        TenantContext.setTenantId("acme");
        try {
            when(timeseriesRepository.findByIdOrExternalId(isNull(), eq("sensor_a")))
                    .thenReturn(java.util.Optional.of(timeseriesEntity(5L, "sensor_a", "FLOAT")));

            timeseriesService.deleteDatapoints(deleteWindow("1767225600000", "1767312000000"));

            // Epoch millis is a documented form, so it must reach the consumer as something the
            // consumer can apply — not as a message that dead-letters after the caller saw 204.
            var captor = ArgumentCaptor.forClass(DataWrapperBin.class);
            verify(allDatapointProducer).send(captor.capture());
            DataCollectionBin item = captor.getValue().getItems().iterator().next();
            assertEquals("2026-01-01T00:00:00Z", item.getInclusiveBegin());
            assertEquals("2026-01-02T00:00:00Z", item.getExclusiveEnd());
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    void deleteDatapoints_rejectsAnUnparseableBoundInsteadOfPublishingIt() throws Exception {
        when(timeseriesRepository.findByIdOrExternalId(isNull(), eq("sensor_a")))
                .thenReturn(java.util.Optional.of(timeseriesEntity(5L, "sensor_a", "FLOAT")));

        BadRequestException thrown = assertThrows(BadRequestException.class,
                () -> timeseriesService.deleteDatapoints(deleteWindow("last tuesday", null)));

        assertTrue(thrown.getError().getError().getMessage().contains("inclusiveBegin"));
        // Nothing may go out: a bad window that reached the consumer would just nack until it DLQ'd.
        verifyNoInteractions(allDatapointProducer);
    }

    @Test
    void deleteDatapoints_withNoBoundsAtAll_clearsTheWholeSeries() throws Exception {
        TenantContext.setTenantId("acme");
        try {
            when(timeseriesRepository.findByIdOrExternalId(isNull(), eq("sensor_a")))
                    .thenReturn(java.util.Optional.of(timeseriesEntity(5L, "sensor_a", "FLOAT")));

            timeseriesService.deleteDatapoints(deleteWindow(null, null));

            // Both bounds are optional, so an item with neither is the documented "empty this
            // series but keep its definition" call — it must reach the consumer fully open rather
            // than NPE on the way, which is what parsing an absent bound used to do.
            var captor = ArgumentCaptor.forClass(DataWrapperBin.class);
            verify(allDatapointProducer).send(captor.capture());
            DataCollectionBin item = captor.getValue().getItems().iterator().next();
            assertNull(item.getInclusiveBegin());
            assertNull(item.getExclusiveEnd());
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    void deleteDatapoints_whenOnlyAnUnknownExternalIdIsNamed_is400NotAnNpe() throws Exception {
        when(timeseriesRepository.findByIdOrExternalId(isNull(), eq("sensor_a")))
                .thenReturn(java.util.Optional.empty());

        // The id is null on an externalId-only item, so the old error path died reading it and the
        // caller got a 500 for what the endpoint documents as a 400.
        BadRequestException thrown = assertThrows(BadRequestException.class,
                () -> timeseriesService.deleteDatapoints(deleteWindow("1767225600000", null)));

        assertTrue(thrown.getError().getError().getMessage().contains("sensor_a"));
        verifyNoInteractions(allDatapointProducer);
    }

    private static DataRetriever<DeleteDatapoint> deleteWindow(String begin, String end) {
        DeleteDatapoint ddp = new DeleteDatapoint();
        ddp.setExternalId("sensor_a");
        ddp.setInclusiveBegin(begin);
        ddp.setExclusiveEnd(end);
        DataRetriever<DeleteDatapoint> r = new DataRetriever<>();
        r.getItems().add(ddp);
        return r;
    }

    private static TimeseriesEntity timeseriesEntity(long id, String externalId, String valueType) {
        TimeseriesEntity e = new TimeseriesEntity();
        e.setId(id);
        e.setExternalId(externalId);
        // Set the name explicitly: setValueType(String) only fills in the id, but the ClickHouse
        // table name is resolved from the name — the same shape a DB-loaded row has.
        e.setValueType(new TimeseriesValueType(TimeseriesValueType.getValueTypeId(valueType), valueType));
        return e;
    }

    private static DataWrapper<IdCollection> idCollectionFor(String... externalIds) {
        DataWrapper<IdCollection> w = new DataWrapper<>();
        for (String externalId : externalIds) {
            IdCollection item = new IdCollection();
            item.setExternalId(externalId);
            w.getItems().add(item);
        }
        return w;
    }

    // ---- readListForDataSet: hierarchy expansion + ACL narrowing --------------------------------
    // The hierarchy walk itself lives in DatasetClosureService.closureOf (the recursive
    // Postgres closure, shared with grant expansion); these
    // cover how the service composes its result with the caller's dataset ACL.

    @Test
    void readListForDataSet_expandsHierarchyForReadAllCaller() {
        when(dataSecurity.hasReadAccessToEverything()).thenReturn(true);
        when(datasetClosureService.closureOf(anyCollection()))
                .thenReturn(java.util.Set.of(10L, 11L, 12L)); // root + child + grandchild

        timeseriesService.readListForDataSet(10L, 500);

        // The timeseries query must be narrowed to the WHOLE closure, not just the clicked root.
        verify(timeseriesRepository).list(eq(500), eq(java.util.Set.of(10L, 11L, 12L)));
    }

    @Test
    void readListForDataSet_intersectsClosureWithCallersReadableDatasets() {
        when(dataSecurity.hasReadAccessToEverything()).thenReturn(false);
        when(dataSecurity.readableDataSetIds()).thenReturn(Set.of(11L, 40L));
        when(datasetClosureService.closureOf(anyCollection()))
                .thenReturn(java.util.Set.of(10L, 11L, 12L));

        timeseriesService.readListForDataSet(10L, 500);

        // Only the readable part of the closure may reach the query — never dataset 10 or 12.
        verify(timeseriesRepository).list(eq(500), eq(java.util.Set.of(11L)));
    }

    @Test
    void readListForDataSet_returnsEmptyWithoutQueryingWhenNothingReadable() {
        when(dataSecurity.hasReadAccessToEverything()).thenReturn(false);
        when(dataSecurity.readableDataSetIds()).thenReturn(Set.of(99L)); // disjoint from closure
        when(datasetClosureService.closureOf(anyCollection()))
                .thenReturn(java.util.Set.of(10L, 11L));

        List<TimeseriesEntity> result = timeseriesService.readListForDataSet(10L, 500);

        assertTrue(result.isEmpty());
        verify(timeseriesRepository, never()).list(anyInt(), anyCollection());
    }

    // ---- filter: the structured POST /timeseries/filter query ----------------------------------

    private static TimeseriesRetreiver retrieverFor(TimeseriesFilter filter) {
        TimeseriesRetreiver r = new TimeseriesRetreiver();
        r.setFilter(filter);
        return r;
    }

    @Test
    void filter_withDataSet_expandsHierarchyAndPassesRemainingCriteria() {
        when(dataSecurity.hasReadAccessToEverything()).thenReturn(true);
        when(datasetClosureService.closureOfReferences(anyCollection()))
                .thenReturn(java.util.Set.of(10L, 11L));
        when(timeseriesRepository.filter(anyInt(), anyCollection(), any(), any(), any()))
                .thenReturn(List.of());
        when(edgeRepository.findAllByEndIn(anyList(), eq(EdgeEntity.class))).thenReturn(List.of());

        TimeseriesFilter filter = new TimeseriesFilter();
        filter.setDataSetId(List.of(IdCollection.createFromId(10L)));
        filter.setUnit(List.of("bar"));
        filter.setValueType(List.of("FLOAT"));
        timeseriesService.filter(retrieverFor(filter));

        // The repository now takes the filter object rather than a positional parameter list, so
        // this asserts the whole criteria set reached it — not just the four fields it used to carry.
        verify(timeseriesRepository).filter(eq(1000), eq(java.util.Set.of(10L, 11L)), eq(filter),
                eq(NodeSort.DEFAULT), isNull());
    }

    @Test
    void filter_withoutDataSet_narrowsToCallersReadableDatasets() {
        when(dataSecurity.hasReadAccessToEverything()).thenReturn(false);
        when(dataSecurity.readableDataSetIds()).thenReturn(Set.of(7L));
        when(timeseriesRepository.filter(anyInt(), anyCollection(), any(), any(), any()))
                .thenReturn(List.of());
        when(edgeRepository.findAllByEndIn(anyList(), eq(EdgeEntity.class))).thenReturn(List.of());

        TimeseriesFilter filter = new TimeseriesFilter();
        filter.setUnitExternalId(List.of("temperature_deg_c"));
        timeseriesService.filter(retrieverFor(filter));

        verify(timeseriesRepository).filter(eq(1000), eq(Set.of(7L)), eq(filter),
                eq(NodeSort.DEFAULT), isNull());
        verify(datasetClosureService, never()).closureOfReferences(anyCollection());
    }

    @Test
    void filter_whenClosureHasNothingReadable_returnsEmptyWithoutQuerying() {
        when(dataSecurity.hasReadAccessToEverything()).thenReturn(false);
        when(dataSecurity.readableDataSetIds()).thenReturn(Set.of(99L));
        when(datasetClosureService.closureOfReferences(anyCollection()))
                .thenReturn(java.util.Set.of(10L, 11L));

        TimeseriesFilter filter = new TimeseriesFilter();
        filter.setDataSetId(List.of(IdCollection.createFromId(10L)));
        DataWrapper<Timeseries> result = timeseriesService.filter(retrieverFor(filter));

        assertTrue(result.getItems().isEmpty());
        verify(timeseriesRepository, never()).filter(anyInt(), anyCollection(), any(), any(), any());
    }

    // ---- search: POST /timeseries/search ------------------------------------------------------
    // Search is POST /timeseries/filter plus a phrase predicate, in one query. It used to run the
    // phrase natively up to a 10 000-row candidate ceiling and then re-ask the filter query about
    // those ids, which cost a round trip and, past the ceiling, dropped rows matching both.

    private static SearchBody<TimeseriesFilter> searchFor(String query, TimeseriesFilter filter) {
        SearchBody<TimeseriesFilter> cmd = new SearchBody<>();
        SearchForm sf = new SearchForm();
        sf.setQuery(query);
        cmd.setSearch(sf);
        cmd.setFilter(filter);
        return cmd;
    }

    @Test
    void search_sendsThePhraseAndTheFilterToOneQuery() {
        when(dataSecurity.hasReadAccessToEverything()).thenReturn(true);
        when(timeseriesRepository.search(anyString(), anyInt(), any(), any())).thenReturn(List.of());
        when(edgeRepository.findAllByEndIn(anyList(), eq(EdgeEntity.class))).thenReturn(List.of());

        TimeseriesFilter filter = new TimeseriesFilter();
        filter.setLabels(List.of("PUMP"));
        SearchBody<TimeseriesFilter> cmd = searchFor("pump", filter);
        cmd.setLimit(25);
        timeseriesService.search(cmd);

        // The caller's own limit, not a candidate ceiling, and the filter travels with the phrase.
        verify(timeseriesRepository).search("pump", 25, null, filter);
    }

    @Test
    void search_narrowsTheDataSetScopeToWhatTheCallerMayRead() {
        when(dataSecurity.hasReadAccessToEverything()).thenReturn(false);
        when(dataSecurity.readableDataSetIds()).thenReturn(Set.of(10L, 11L));
        when(timeseriesRepository.search(anyString(), anyInt(), any(), any())).thenReturn(List.of());
        when(edgeRepository.findAllByEndIn(anyList(), eq(EdgeEntity.class))).thenReturn(List.of());

        timeseriesService.search(searchFor("pump", null));

        verify(timeseriesRepository).search("pump", 100, Set.of(10L, 11L), null);
    }

    @Test
    void search_withNoReadableDataSetsReturnsNothingWithoutQuerying() {
        when(dataSecurity.hasReadAccessToEverything()).thenReturn(false);
        when(dataSecurity.readableDataSetIds()).thenReturn(Set.of());

        DataWrapper<Timeseries> result = timeseriesService.search(searchFor("pump", null));

        assertTrue(result.getItems().isEmpty());
        verify(timeseriesRepository, never()).search(anyString(), anyInt(), any(), any());
    }
    private static TimeseriesEntity tsEntity(long id, String externalId) {
        TimeseriesEntity e = new TimeseriesEntity();
        e.setId(id);
        e.setExternalId(externalId);
        e.setName("Temp");
        e.setLabels("TIMESERIES");
        e.setValueType(new ai.intellistream.datahub.jpa.domains.TimeseriesValueType(7)); // float32 — avoids getTableType NPE
        return e;
    }

    @Test
    void save_authorizesAndPublishesTimeseriesEvent() throws Exception {
        // Safety net before the create-engine unification: a timeseries create must authorize the
        // dataset and publish exactly one TIMESERIES (not RESOURCE_AND_RELATION) CUD event.
        ai.intellistream.datahub.tenant.TenantContext.setTenantId("tenant-1");
        try {
            when(validator.validate(any())).thenReturn(Collections.emptySet());
            when(nodeRepository.findAllByExternalIdHashIn(anyList(), eq(NameAndExternalId.class)))
                    .thenReturn(List.of());
            TimeseriesEntity saved = tsEntity(1L, "temperature");
            when(nodeService.mapTimeseriesFrom(any())).thenReturn(List.of(saved));
            when(timeseriesRepository.saveAll(anyList())).thenReturn(List.of(saved));
            when(edgeRepository.saveAll(anyList())).thenReturn(List.of());

            timeseriesService.save(wrap(ts("temperature")));

            org.mockito.Mockito.verify(dataSecurity).assertCanWriteDataSet(null);
            org.mockito.ArgumentCaptor<ai.intellistream.datahub.api.messaging.events.ResourceCudPublishEvent> cap =
                    org.mockito.ArgumentCaptor.forClass(ai.intellistream.datahub.api.messaging.events.ResourceCudPublishEvent.class);
            org.mockito.Mockito.verify(applicationEventPublisher).publishEvent(cap.capture());
            assertEquals(ai.intellistream.datahub.pulsar.EventObject.TIMESERIES,
                    cap.getValue().message().getEventObject());
        } finally {
            ai.intellistream.datahub.tenant.TenantContext.clear();
        }
    }

    @Test
    void save_createsPublishDataToEdgeFromRelatedResource() throws Exception {
        // The auto-edge behaviour that must survive any create unification: a relatedResource with
        // no explicit relationship type becomes a PUBLISH_DATA_TO edge into the timeseries.
        ai.intellistream.datahub.tenant.TenantContext.setTenantId("tenant-1");
        try {
            when(validator.validate(any())).thenReturn(Collections.emptySet());
            when(nodeRepository.findAllByExternalIdHashIn(anyList(), eq(NameAndExternalId.class)))
                    .thenReturn(List.of());
            TimeseriesEntity saved = tsEntity(1L, "temperature");
            when(nodeService.mapTimeseriesFrom(any())).thenReturn(List.of(saved));
            when(timeseriesRepository.saveAll(anyList())).thenReturn(List.of(saved));
            ai.intellistream.datahub.jpa.dto.NameAndEId src = mock(ai.intellistream.datahub.jpa.dto.NameAndEId.class);
            when(src.getId()).thenReturn(5L);
            when(nodeRepository.findAllByIdOrExternalId(any(), any(),
                    eq(ai.intellistream.datahub.jpa.dto.NameAndEId.class))).thenReturn(List.of(src));
            ai.intellistream.datahub.jpa.domains.RelationshipType relType =
                    mock(ai.intellistream.datahub.jpa.domains.RelationshipType.class);
            when(relType.getName()).thenReturn("PUBLISH_DATA_TO");
            when(resourceService.mapEdge(any(), any())).thenAnswer(inv -> {
                EdgeEntity e = inv.getArgument(0);
                e.setId(10L);
                e.setStart(5L);
                e.setEnd(1L);
                e.setRelationshipType(relType);
                return e;
            });
            when(edgeRepository.saveAll(anyList()))
                    .thenAnswer(inv -> new java.util.ArrayList<>((java.util.Collection<EdgeEntity>) inv.getArgument(0)));

            Timeseries ts = ts("temperature");
            ts.getRelatedResources().add(ai.intellistream.datahub.models.RelatedNode.createFromId(5L));
            timeseriesService.save(wrap(ts));

            org.mockito.ArgumentCaptor<ai.intellistream.datahub.models.RelForm> relCap =
                    org.mockito.ArgumentCaptor.forClass(ai.intellistream.datahub.models.RelForm.class);
            org.mockito.Mockito.verify(resourceService).mapEdge(any(), relCap.capture());
            assertEquals("PUBLISH_DATA_TO", relCap.getValue().getRelationshipType());
            assertEquals("temperature", relCap.getValue().getToExternalId());
        } finally {
            ai.intellistream.datahub.tenant.TenantContext.clear();
        }
    }

}
