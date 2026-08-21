// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.services;

import ai.intellistream.datahub.api.controllers.errors.BadRequestException;
import ai.intellistream.datahub.api.datasecurity.DataSecurity;
import ai.intellistream.datahub.api.messaging.events.EventCudPublishEvent;
import ai.intellistream.datahub.api.responses.DataWrapper;
import ai.intellistream.datahub.clickhouse.ClickHouseEventService;
import ai.intellistream.datahub.helpers.text.ExternalIds;
import ai.intellistream.datahub.jpa.domains.NodeType;
import ai.intellistream.datahub.jpa.dto.NameAndExternalId;
import ai.intellistream.datahub.models.EventModel;
import ai.intellistream.datahub.models.IdCollection;
import ai.intellistream.datahub.models.UUIDAndExternalIdCollection;
import ai.intellistream.datahub.models.UpdateEventForm;
import ai.intellistream.datahub.models.events.EventFilter;
import ai.intellistream.datahub.models.events.EventRetreiver;
import ai.intellistream.datahub.pulsar.EventCudMessage;
import ai.intellistream.datahub.repositories.event.EventDimensionRepository;
import ai.intellistream.datahub.repositories.node.DataSetRepository;
import ai.intellistream.datahub.repositories.node.NodeRepository;
import ai.intellistream.datahub.services.KVRocksService;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.apache.pulsar.client.api.Producer;
import org.apache.pulsar.client.api.TypedMessageBuilder;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EventServiceTest {

    @Mock private KVRocksService kvRocksService;
    @Mock private ClickHouseEventService clickHouseEventService;
    @Mock private ApplicationEventPublisher applicationEventPublisher;
    @Mock private Producer<EventCudMessage> eventMessageProducer;
    @Mock private Validator validator;
    @Mock private NodeRepository nodeRepository;
    @Mock private DataSecurity dataSecurity;
    @Mock private EventDimensionRepository eventDimensionRepository;
    @Mock private DataSetRepository dataSetRepository;
    @Mock private ai.intellistream.datahub.api.datasecurity.DatasetClosureService datasetClosureService;

    /**
     * By default a data set has no children, so the closure is the roots themselves — that keeps
     * these tests about *resolution*. The expansion itself is asserted separately below, and its
     * own logic is covered by DatasetClosureServiceTest.
     */
    @org.junit.jupiter.api.BeforeEach
    void closureIsIdentityByDefault() {
        org.mockito.Mockito.lenient().when(datasetClosureService.closureOf(org.mockito.ArgumentMatchers.anyCollection()))
                .thenAnswer(inv -> new java.util.LinkedHashSet<>(inv.getArgument(0, java.util.Collection.class)));
    }

    @InjectMocks
    private EventService eventService;

    @Test
    void create_ShouldGenerateIdsAndSendMessage() throws Exception {
        // Arrange
        EventModel event = new EventModel();
        event.setExternalId("ext-1");
        DataWrapper<EventModel> input = new DataWrapper<>();
        input.getItems().add(event);

        when(validator.validate(any())).thenReturn(Collections.emptySet());
        // Mock nodeRepository to pass dataSet and resource validation...

        // Act
        DataWrapper<EventModel> result = eventService.create(input);

        // Assert
        assertNotNull(result.getItems().stream().toList().getFirst().getId());
        verify(kvRocksService).saveEvents(any());
        verify(applicationEventPublisher).publishEvent(any(EventCudPublishEvent.class));
    }

    @Test
    void create_WhenClientSuppliesValidUuid_ShouldPreserveIt() throws Exception {
        // Arrange — a client-supplied UUID id (lets the SDK dedup retries via ReplacingMergeTree).
        EventModel event = new EventModel();
        event.setExternalId("ext-1");
        String clientId = UUID.randomUUID().toString();
        event.setId(clientId);
        DataWrapper<EventModel> input = new DataWrapper<>();
        input.getItems().add(event);
        when(validator.validate(any())).thenReturn(Collections.emptySet());

        // Act
        DataWrapper<EventModel> result = eventService.create(input);

        // Assert — the supplied id is kept, not overwritten.
        assertEquals(clientId, result.getItems().stream().toList().getFirst().getId());
    }

    @Test
    void create_WhenClientIdNotAUuid_ShouldGenerateOne() throws Exception {
        // Arrange — a non-UUID id must not be honored (KVRocks/update parse it as a UUID).
        EventModel event = new EventModel();
        event.setExternalId("ext-1");
        event.setId("not-a-uuid");
        DataWrapper<EventModel> input = new DataWrapper<>();
        input.getItems().add(event);
        when(validator.validate(any())).thenReturn(Collections.emptySet());

        // Act
        DataWrapper<EventModel> result = eventService.create(input);

        // Assert — a fresh, valid UUID was generated instead.
        String id = result.getItems().stream().toList().getFirst().getId();
        assertNotEquals("not-a-uuid", id);
        assertDoesNotThrow(() -> UUID.fromString(id));
    }

    @Test
    void create_WhenValidationFails_ShouldThrowException() {
        // Arrange
        DataWrapper<EventModel> input = new DataWrapper<>();
        input.getItems().add(new EventModel());
        Set<ConstraintViolation<DataWrapper<EventModel>>> violations = Set.of(mock(ConstraintViolation.class));
        when(validator.validate(input)).thenReturn(violations);

        // Act & Assert
        assertThrows(ConstraintViolationException.class, () -> eventService.create(input));
        verifyNoInteractions(applicationEventPublisher);
    }

    @Test
    void delete_WhenExternalIdNotFound_ShouldStillSendPulsarMessageWithExtId() throws Exception {
        // Arrange
        String extId = "non-existent";
        UUIDAndExternalIdCollection item = new UUIDAndExternalIdCollection();
        item.setExternalId(extId);
        DataWrapper<UUIDAndExternalIdCollection> input = new DataWrapper<>();
        input.getItems().add(item);

        when(kvRocksService.findEventIdsByExternalIdCollectionAsMap(anySet())).thenReturn(Collections.emptyMap());

        @SuppressWarnings("unchecked")
        TypedMessageBuilder<EventCudMessage> messageBuilder = mock(TypedMessageBuilder.class);
        when(eventMessageProducer.newMessage()).thenReturn(messageBuilder);
        when(messageBuilder.key(any())).thenReturn(messageBuilder);
        when(messageBuilder.value(any())).thenReturn(messageBuilder);

        // Act
        eventService.delete(input);

        // Assert — delete() sends synchronously through the producer, keyed by tenantId so the
        // DELETE lands on the same partition as the tenant's other CUD on the partitioned events
        // topic. Verify the keyed builder send rather than an application event.
        verify(messageBuilder).send();
        ArgumentCaptor<EventCudMessage> messageCaptor = ArgumentCaptor.forClass(EventCudMessage.class);
        verify(messageBuilder).value(messageCaptor.capture());

        EventModel sentEvent = messageCaptor.getValue().getEvents().get(0);
        assertNull(sentEvent.getId());
        // Verbatim: the submitted "non-existent" is echoed back unchanged. EventModel used to
        // snake_case it on read, so a caller saw an id they had never sent.
        assertEquals("non-existent", sentEvent.getExternalId());
    }

    @Test
    void findAllByIdAndExternalId_ShouldCombineResults() {
        // Arrange
        UUID id1 = UUID.randomUUID();
        String extId2 = "ext-2";
        UUID id2 = UUID.randomUUID();

        UUIDAndExternalIdCollection item1 = new UUIDAndExternalIdCollection();
        item1.setId(id1);
        UUIDAndExternalIdCollection item2 = new UUIDAndExternalIdCollection();
        item2.setExternalId(extId2);

        when(kvRocksService.findEventIdsByExternalIdCollection(anySet())).thenReturn(List.of(id2));
        // Read-all caller → no dataset filter (the ClickHouse call gets a null allowed-set).
        when(dataSecurity.hasReadAccessToEverything()).thenReturn(true);
        when(clickHouseEventService.findAllById(anySet(), any(), any(), any())).thenReturn(List.of(new EventModel(), new EventModel()));

        // Act
        DataWrapper<EventModel> result = eventService.findAllByIdAndExternalId(List.of(item1, item2));

        // Assert
        assertEquals(2, result.getItems().size());
        verify(clickHouseEventService).findAllById(argThat(set -> set.contains(id1) && set.contains(id2)), any(), any(), any());
    }

    @Test
    void listTypes_readAllCaller_passesNullAclAndDelegatesToRepository() {
        // Read-all caller → ACL is null (no dataset restriction in SQL).
        when(dataSecurity.hasReadAccessToEverything()).thenReturn(true);
        when(eventDimensionRepository.listDistinct(EventDimensionRepository.Dimension.TYPE, null, "ala", 50))
                .thenReturn(List.of("alarm"));

        DataWrapper<String> result = eventService.listTypes("ala", 50);

        assertEquals(List.of("alarm"), new ArrayList<>(result.getItems()));
        verify(eventDimensionRepository).listDistinct(EventDimensionRepository.Dimension.TYPE, null, "ala", 50);
    }

    @Test
    void listStatuses_restrictedCaller_passesReadableDatasetsAsAcl() {
        Set<Long> readable = Set.of(1L, 2L);
        when(dataSecurity.hasReadAccessToEverything()).thenReturn(false);
        when(dataSecurity.readableDataSetIds()).thenReturn(readable);
        when(eventDimensionRepository.listDistinct(eq(EventDimensionRepository.Dimension.STATUS), eq(readable), isNull(), eq(1000)))
                .thenReturn(List.of("closed", "open"));

        DataWrapper<String> result = eventService.listStatuses(null, 1000);

        assertEquals(2, result.getItems().size());
        verify(eventDimensionRepository).listDistinct(EventDimensionRepository.Dimension.STATUS, readable, null, 1000);
    }

    @Test
    void listSubTypes_usesSubTypeDimension() {
        when(dataSecurity.hasReadAccessToEverything()).thenReturn(true);
        when(eventDimensionRepository.listDistinct(EventDimensionRepository.Dimension.SUB_TYPE, null, null, 1000))
                .thenReturn(List.of("electrical"));

        eventService.listSubTypes(null, 1000);

        verify(eventDimensionRepository).listDistinct(EventDimensionRepository.Dimension.SUB_TYPE, null, null, 1000);
    }

    @Test
    void listSources_usesSourceDimension() {
        when(dataSecurity.hasReadAccessToEverything()).thenReturn(true);
        when(eventDimensionRepository.listDistinct(EventDimensionRepository.Dimension.SOURCE, null, "sa", 1000))
                .thenReturn(List.of("SAP"));

        DataWrapper<String> result = eventService.listSources("sa", 1000);

        assertEquals(List.of("SAP"), new ArrayList<>(result.getItems()));
        verify(eventDimensionRepository).listDistinct(EventDimensionRepository.Dimension.SOURCE, null, "sa", 1000);
    }

    @Test
    void update_batchTargetedByIdWithNullExternalIds_matchesEachWithoutNpe() throws Exception {
        // Two events, each targeted by id only (the forms carry no externalId). While matching the
        // second event, the form for the first event doesn't match by id and has a null externalId —
        // which used to NPE in the match predicate. Each event must instead resolve to its own form.
        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();
        EventModel em1 = new EventModel(); em1.setId(id1.toString()); em1.setExternalId("ext-1");
        EventModel em2 = new EventModel(); em2.setId(id2.toString()); em2.setExternalId("ext-2");

        UpdateEventForm f1 = new UpdateEventForm().setId(id1);
        UpdateEventForm f2 = new UpdateEventForm().setId(id2);
        DataWrapper<UpdateEventForm> input = new DataWrapper<>();
        input.getItems().add(f1);
        input.getItems().add(f2);

        when(clickHouseEventService.findAllById(anySet(), any(), any())).thenReturn(List.of(em1, em2));
        when(kvRocksService.findEventIdsByExternalIdCollectionAsMap(anySet())).thenReturn(Map.of());
        when(nodeRepository.findAllByIdIn(anySet(), any())).thenReturn(List.of());
        when(nodeRepository.findAllByExternalIdHashIn(anyList(), any())).thenReturn(List.of());

        assertDoesNotThrow(() -> eventService.update(input));
        verify(applicationEventPublisher).publishEvent(any(EventCudPublishEvent.class));
    }

    @Test
    void validateAndUpdate_metadataAdd_addedValuesWinOverExisting() {
        // metadata.add must layer new entries ON TOP of existing ones: a colliding key takes the
        // added value; non-colliding existing keys are kept.
        EventModel em = new EventModel();
        em.setMetadata(new HashMap<>(Map.of("keep", "old", "shared", "old-value")));

        UpdateEventForm form = new UpdateEventForm();
        form.getUpdate().getMetadata().setAdd(new HashMap<>(Map.of("shared", "new-value", "added", "x")));

        eventService.validateAndUpdate(form, em, Map.of(), Map.of());

        assertEquals("old", em.getMetadata().get("keep"), "existing key not in add must be kept");
        assertEquals("new-value", em.getMetadata().get("shared"), "colliding key must take the added value");
        assertEquals("x", em.getMetadata().get("added"), "new key must be added");
    }

    // ---- update: fields that can and cannot be cleared -------------------------------------------

    @Test
    void update_movingAnEventIntoAnUnknownDataSet_isRejected() {
        // The ACL check answers "may you write dataset 999?", not "does dataset 999 exist?" — and a
        // caller with a write-everything grant passes it for any id at all. Create has always
        // looked the dataset up; update did not, so it stored events pointing at nothing.
        UUID id = UUID.randomUUID();
        EventModel em = new EventModel(); em.setId(id.toString()); em.setExternalId("ext-1");

        UpdateEventForm form = new UpdateEventForm().setId(id);
        form.getUpdate().getDataSetId().set(999L);
        DataWrapper<UpdateEventForm> input = new DataWrapper<>();
        input.getItems().add(form);

        when(clickHouseEventService.findAllById(anySet(), any(), any())).thenReturn(List.of(em));
        when(nodeRepository.findAllByIdAsIdList(anySet())).thenReturn(List.of());

        BadRequestException ex = assertThrows(BadRequestException.class, () -> eventService.update(input));
        assertTrue(ex.getError().getError().getFields().stream()
                        .anyMatch(it -> "999".equals(it.get("dataSet"))),
                "the error must name the dataset that could not be found");
        verify(applicationEventPublisher, never()).publishEvent(any(EventCudPublishEvent.class));
    }

    @Test
    void update_knownDataSet_isAccepted() {
        UUID id = UUID.randomUUID();
        EventModel em = new EventModel(); em.setId(id.toString()); em.setExternalId("ext-1");

        UpdateEventForm form = new UpdateEventForm().setId(id);
        form.getUpdate().getDataSetId().set(42L);
        DataWrapper<UpdateEventForm> input = new DataWrapper<>();
        input.getItems().add(form);

        when(clickHouseEventService.findAllById(anySet(), any(), any())).thenReturn(List.of(em));
        when(nodeRepository.findAllByIdAsIdList(anySet())).thenReturn(List.of(42L));
        when(kvRocksService.findEventIdsByExternalIdCollectionAsMap(anySet())).thenReturn(Map.of());
        when(nodeRepository.findAllByIdIn(anySet(), any())).thenReturn(List.of());
        when(nodeRepository.findAllByExternalIdHashIn(anyList(), any())).thenReturn(List.of());

        assertDoesNotThrow(() -> eventService.update(input));
        assertEquals(42L, em.getDataSetId());
    }

    @Test
    void validateAndUpdate_dataSetIdSetNull_detachesTheEvent() {
        // An event's dataset is genuinely optional (0 is the ClickHouse "no dataset" sentinel), so
        // unlike type and externalId this one can honestly be cleared. It used to be a silent no-op.
        EventModel em = new EventModel();
        em.setDataSetId(42L);

        UpdateEventForm form = new UpdateEventForm();
        form.getUpdate().getDataSetId().setNull(true);

        eventService.validateAndUpdate(form, em, Map.of(), Map.of());

        assertNull(em.getDataSetId(), "setNull on an event's dataSetId must detach it");
    }

    @Test
    void validateAndUpdate_typeSetNull_isRejectedRatherThanBrickingTheEvent() {
        // Honouring this wrote an empty string into the non-nullable events.type column, after
        // which every read of the event failed against a client model that declares type required.
        EventModel em = new EventModel();
        em.setType("Alarm");

        UpdateEventForm form = new UpdateEventForm();
        form.getUpdate().getType().setNull(true);

        assertThrows(BadRequestException.class,
                () -> eventService.validateAndUpdate(form, em, Map.of(), Map.of()));
        assertEquals("Alarm", em.getType(), "a rejected update must leave the event untouched");
    }

    // ---- data set references --------------------------------------------------------------------
    //
    // filter.dataSetIds names data sets by id or externalId. Only this layer can resolve a name, so
    // it rewrites the list as ids before ClickHouse ever sees it.

    private static EventRetreiver retrieverFilteringOn(List<IdCollection> dataSets) {
        EventFilter filter = new EventFilter();
        filter.setDataSetId(dataSets);
        EventRetreiver retreiver = new EventRetreiver();
        retreiver.setFilter(filter);
        return retreiver;
    }

    /** The ids the retriever carried by the time it reached ClickHouse. */
    private List<Long> capturedDataSetIds() {
        ArgumentCaptor<EventRetreiver> captor = ArgumentCaptor.forClass(EventRetreiver.class);
        verify(clickHouseEventService).filter(captor.capture(), any());
        return captor.getValue().getFilter().getDataSetId().stream().map(IdCollection::getId).toList();
    }

    /**
     * Resolving id-or-externalId references and expanding the hierarchy both live in
     * {@code DatasetClosureService.closureOfReferences} now — one implementation for events,
     * resources and timeseries, covered by {@code DatasetClosureServiceTest}. What is left for this
     * service to get right is handing the references over and writing the answer back onto the
     * filter, which is what these cover.
     */
    @Test
    void filter_replacesTheReferencesWithTheResolvedClosure() {
        when(datasetClosureService.closureOfReferences(any()))
                .thenReturn(new java.util.LinkedHashSet<>(List.of(12L, 13L, 14L)));

        eventService.filter(retrieverFilteringOn(List.of(
                IdCollection.createFromId(12L),
                IdCollection.createFromExternalId("data_set_sap"))));

        assertEquals(List.of(12L, 13L, 14L), capturedDataSetIds());
    }

    /**
     * A closure that resolves to nothing leaves an EMPTY id list, not a null one — the caller asked
     * to be narrowed to those data sets, so the query must match nothing rather than everything.
     */
    @Test
    void filter_whenNothingResolves_narrowsToNothingRatherThanEverything() {
        when(datasetClosureService.closureOfReferences(any())).thenReturn(Set.of());

        eventService.filter(retrieverFilteringOn(List.of(IdCollection.createFromExternalId("data_set_typo"))));

        assertTrue(capturedDataSetIds().isEmpty());
    }

    /** Absent means unfiltered: nothing to resolve, and null must survive to the storage layer. */
    @Test
    void filter_withoutDataSetIds_looksNothingUpAndStaysNull() {
        eventService.filter(retrieverFilteringOn(null));

        ArgumentCaptor<EventRetreiver> captor = ArgumentCaptor.forClass(EventRetreiver.class);
        verify(clickHouseEventService).filter(captor.capture(), any());
        assertNull(captor.getValue().getFilter().getDataSetId());
        verify(datasetClosureService, never()).closureOfReferences(any());
    }

    // ---------------------------------------------------------------------------------------
    // Related resources. One list of IdCollection replaced two parallel id/externalId lists, so
    // these pin the resolution rules that make the two sides unable to disagree.
    // ---------------------------------------------------------------------------------------

    /** Minimal stand-in for the JPA projection the node lookups return. */
    private record Node(Long getIdValue, String getExternalIdValue) implements NameAndExternalId {
        @Override public Long getId() { return getIdValue; }
        @Override public String getName() { return getExternalIdValue; }
        @Override public String getExternalId() { return getExternalIdValue; }
        @Override public Long getExternalIdHash() { return ExternalIds.hash(getExternalIdValue); }
    }

    private static final NameAndExternalId PUMP = new Node(7L, "pump_7");
    private static final NameAndExternalId SENSOR = new Node(34L, "sensor_abc");

    /** Wires the node lookups so every resource in {@code nodes} resolves and nothing else does. */
    private void givenResources(NameAndExternalId... nodes) {
        List<NameAndExternalId> all = List.of(nodes);
        // Lenient: validateAndFetchResources short-circuits on the first failing lookup, so the
        // second stub goes unused whenever a test asserts a rejection.
        lenient().when(validator.validate(any())).thenReturn(Collections.emptySet());
        lenient().when(nodeRepository.findAllByIdIn(anySet(), any())).thenAnswer(inv -> {
            Set<Long> wanted = inv.getArgument(0);
            return all.stream().filter(it -> wanted.contains(it.getId())).toList();
        });
        lenient().when(nodeRepository.findAllByExternalIdHashIn(anyList(), any())).thenAnswer(inv -> {
            List<Long> wanted = inv.getArgument(0);
            return all.stream().filter(it -> wanted.contains(it.getExternalIdHash())).toList();
        });
    }

    private DataWrapper<EventModel> eventWithRelated(IdCollection... related) {
        EventModel event = new EventModel();
        event.setExternalId("ext-1");
        event.setRelatedResources(new ArrayList<>(List.of(related)));
        DataWrapper<EventModel> input = new DataWrapper<>();
        input.getItems().add(event);
        return input;
    }

    @Test
    void create_relatedResourceGivenByIdOnly_getsItsExternalIdFilledIn() throws Exception {
        givenResources(PUMP);

        var result = eventService.create(eventWithRelated(IdCollection.createFromId(7L)));

        IdCollection resolved = result.getItems().stream().toList().getFirst().getRelatedResources().getFirst();
        assertEquals(7L, resolved.getId());
        assertEquals("pump_7", resolved.getExternalId());
    }

    @Test
    void create_relatedResourceGivenByExternalIdOnly_getsItsIdFilledIn() throws Exception {
        givenResources(PUMP);

        var result = eventService.create(eventWithRelated(IdCollection.createFromExternalId("pump_7")));

        IdCollection resolved = result.getItems().stream().toList().getFirst().getRelatedResources().getFirst();
        assertEquals(7L, resolved.getId());
        assertEquals("pump_7", resolved.getExternalId());
    }

    @Test
    void create_consistentPair_isAcceptedUnchanged() throws Exception {
        givenResources(PUMP);
        IdCollection both = new IdCollection();
        both.setId(7L);
        both.setExternalId("pump_7");

        var result = eventService.create(eventWithRelated(both));

        List<IdCollection> resolved = result.getItems().stream().toList().getFirst().getRelatedResources();
        assertEquals(1, resolved.size());
        assertEquals(7L, resolved.getFirst().getId());
        assertEquals("pump_7", resolved.getFirst().getExternalId());
    }

    @Test
    void create_mismatchedPair_isRejected() {
        // The drift bug at its source: the old model silently unioned this into an event
        // describing BOTH resources. It must now be a 400.
        givenResources(PUMP, SENSOR);
        IdCollection mismatched = new IdCollection();
        mismatched.setId(7L);
        mismatched.setExternalId("sensor_abc");

        assertThrows(BadRequestException.class, () -> eventService.create(eventWithRelated(mismatched)));
    }

    @Test
    void create_unknownResource_isRejected() {
        givenResources(PUMP);

        assertThrows(BadRequestException.class,
                () -> eventService.create(eventWithRelated(IdCollection.createFromId(999L))));
    }

    @Test
    void create_duplicateReferences_areDedupedAndOrderIsStable() throws Exception {
        givenResources(PUMP, SENSOR);

        var result = eventService.create(eventWithRelated(
                IdCollection.createFromExternalId("sensor_abc"),
                IdCollection.createFromId(7L),
                IdCollection.createFromId(34L),          // same resource as sensor_abc
                IdCollection.createFromExternalId("pump_7")));  // same resource as id 7

        List<IdCollection> resolved = result.getItems().stream().toList().getFirst().getRelatedResources();
        assertEquals(List.of(34L, 7L), resolved.stream().map(IdCollection::getId).toList(),
                "first mention wins and order is preserved");
        assertTrue(resolved.stream().allMatch(it -> it.getExternalId() != null),
                "every entry must end up with both sides populated");
    }

    @Test
    void validateAndUpdate_addAndRemove_mergeAgainstTheStoredList() {
        // add/remove used to be advertised on the patch form and silently ignored.
        EventModel em = new EventModel();
        IdCollection stored = new IdCollection();
        stored.setId(7L);
        stored.setExternalId("pump_7");
        em.setRelatedResources(new ArrayList<>(List.of(stored)));

        Map<Long, NameAndExternalId> byId = Map.of(7L, PUMP, 34L, SENSOR);
        Map<Long, NameAndExternalId> byHash = Map.of(
                PUMP.getExternalIdHash(), PUMP, SENSOR.getExternalIdHash(), SENSOR);

        UpdateEventForm form = new UpdateEventForm();
        form.getUpdate().getRelatedResources()
                .add(List.of(IdCollection.createFromExternalId("sensor_abc")))
                .remove(List.of(IdCollection.createFromExternalId("pump_7")));

        eventService.validateAndUpdate(form, em, byId, byHash);

        assertEquals(1, em.getRelatedResources().size());
        assertEquals(34L, em.getRelatedResources().getFirst().getId(), "added entry must remain");
        assertEquals("sensor_abc", em.getRelatedResources().getFirst().getExternalId());
    }

    @Test
    void validateAndUpdate_removeByTheOtherSide_stillDropsTheEntry() {
        // The stored entry carries both sides; the caller names it by id alone. Because the merge
        // keys on the resolved node id, it still matches.
        EventModel em = new EventModel();
        IdCollection stored = new IdCollection();
        stored.setId(7L);
        stored.setExternalId("pump_7");
        em.setRelatedResources(new ArrayList<>(List.of(stored)));

        UpdateEventForm form = new UpdateEventForm();
        form.getUpdate().getRelatedResources().remove(List.of(IdCollection.createFromId(7L)));

        eventService.validateAndUpdate(form, em, Map.of(7L, PUMP), Map.of(PUMP.getExternalIdHash(), PUMP));

        assertTrue(em.getRelatedResources().isEmpty());
    }

    @Test
    void validateAndUpdate_setReplacesThenAddLayersOnTop() {
        EventModel em = new EventModel();
        IdCollection stored = new IdCollection();
        stored.setId(7L);
        stored.setExternalId("pump_7");
        em.setRelatedResources(new ArrayList<>(List.of(stored)));

        Map<Long, NameAndExternalId> byId = Map.of(7L, PUMP, 34L, SENSOR);
        Map<Long, NameAndExternalId> byHash = Map.of(
                PUMP.getExternalIdHash(), PUMP, SENSOR.getExternalIdHash(), SENSOR);

        UpdateEventForm form = new UpdateEventForm();
        form.getUpdate().getRelatedResources()
                .set(List.of(IdCollection.createFromId(34L)))
                .add(List.of(IdCollection.createFromId(7L)));

        eventService.validateAndUpdate(form, em, byId, byHash);

        assertEquals(List.of(34L, 7L), em.getRelatedResources().stream().map(IdCollection::getId).toList());
    }

    @Test
    void validateAndUpdate_untouchedRelatedResources_areLeftAlone() {
        EventModel em = new EventModel();
        IdCollection stored = new IdCollection();
        stored.setId(7L);
        stored.setExternalId("pump_7");
        em.setRelatedResources(new ArrayList<>(List.of(stored)));

        UpdateEventForm form = new UpdateEventForm();
        form.getUpdate().getDescription().set("just a description change");

        eventService.validateAndUpdate(form, em, Map.of(), Map.of());

        assertEquals(1, em.getRelatedResources().size());
        assertEquals(7L, em.getRelatedResources().getFirst().getId());
    }

}
