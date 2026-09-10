// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.models.validation;

import ai.intellistream.datahub.api.responses.DataWrapper;
import ai.intellistream.datahub.api.responses.DatapointString;
import ai.intellistream.datahub.api.responses.DatapointsCollection;
import ai.intellistream.datahub.models.EventModel;
import ai.intellistream.datahub.models.GeoLocation;
import ai.intellistream.datahub.models.RelForm;
import ai.intellistream.datahub.models.Resource;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The size ceilings that stop an entity being used as file storage.
 *
 * <p>Every field asserted here was unbounded at every layer: {@code description} and {@code metadata}
 * carried no constraint, and {@code items}/{@code datapoints} no count, so one request could carry as
 * much as the transport would pass. The at-max cases are asserted alongside the over-max ones because
 * a cap that also rejects legitimate input is its own kind of failure.
 */
class FieldSizeCapsTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void setUp() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void tearDown() {
        factory.close();
    }

    private static String repeat(int length) {
        return "x".repeat(length);
    }

    private static Set<String> paths(Set<? extends ConstraintViolation<?>> violations) {
        return violations.stream()
                .map(v -> v.getPropertyPath().toString())
                .collect(Collectors.toSet());
    }

    private static EventModel validEvent() {
        EventModel event = new EventModel();
        event.setExternalId("valid_event_external_id");
        event.setType("Alarm");
        event.setEventTime(ZonedDateTime.parse("2026-01-01T00:00:00Z"));
        return event;
    }

    private static Resource validResource() {
        Resource resource = new Resource();
        resource.setExternalId("valid_resource_external_id");
        resource.setName("valid resource name");
        resource.setLabels(new ArrayList<>(List.of("ASSET")));
        return resource;
    }

    // ---- description -------------------------------------------------------------------------

    @Test
    void event_descriptionAtMax_isAccepted() {
        EventModel event = validEvent();
        event.setDescription(repeat(FieldLimits.DESCRIPTION_MAX));
        assertTrue(validator.validate(event).isEmpty());
    }

    @Test
    void event_descriptionOverMax_isRejected() {
        EventModel event = validEvent();
        event.setDescription(repeat(FieldLimits.DESCRIPTION_MAX + 1));
        assertTrue(paths(validator.validate(event)).contains("description"));
    }

    @Test
    void resource_descriptionOverMax_isRejected() {
        Resource resource = validResource();
        resource.setDescription(repeat(FieldLimits.DESCRIPTION_MAX + 1));
        assertTrue(paths(validator.validate(resource)).contains("description"));
    }

    @Test
    void relation_descriptionOverMax_isRejected() {
        RelForm rel = new RelForm();
        rel.setRelationshipType("CONNECTED_TO");
        rel.setFromExternalId("a_from_external_id");
        rel.setToExternalId("a_to_external_id");
        rel.setDescription(repeat(FieldLimits.DESCRIPTION_MAX + 1));
        assertTrue(paths(validator.validate(rel)).contains("description"));
    }

    // ---- metadata ----------------------------------------------------------------------------

    @Test
    void event_metadataAtMaxEntries_isAccepted() {
        EventModel event = validEvent();
        event.setMetadata(metadataWithEntries(FieldLimits.METADATA_MAX_ENTRIES));
        assertTrue(validator.validate(event).isEmpty());
    }

    @Test
    void event_tooManyMetadataEntries_isRejected() {
        EventModel event = validEvent();
        event.setMetadata(metadataWithEntries(FieldLimits.METADATA_MAX_ENTRIES + 1));
        assertTrue(paths(validator.validate(event)).contains("metadata"));
    }

    @Test
    void event_metadataValueOverMax_isRejected() {
        EventModel event = validEvent();
        event.setMetadata(new HashMap<>(Map.of("k", repeat(FieldLimits.METADATA_VALUE_MAX + 1))));
        assertTrue(paths(validator.validate(event)).contains("metadata"));
    }

    @Test
    void event_metadataKeyOverMax_isRejected() {
        EventModel event = validEvent();
        event.setMetadata(new HashMap<>(Map.of(repeat(FieldLimits.METADATA_KEY_MAX + 1), "v")));
        assertTrue(paths(validator.validate(event)).contains("metadata"));
    }

    @Test
    void resource_metadataValueOverMax_isRejected() {
        Resource resource = validResource();
        resource.setMetadata(new HashMap<>(Map.of("k", repeat(FieldLimits.METADATA_VALUE_MAX + 1))));
        assertTrue(paths(validator.validate(resource)).contains("metadata"));
    }

    @Test
    void event_metadataKeyAndValueAtMax_areAccepted() {
        EventModel event = validEvent();
        event.setMetadata(new HashMap<>(Map.of(
                repeat(FieldLimits.METADATA_KEY_MAX), repeat(FieldLimits.METADATA_VALUE_MAX))));
        assertTrue(validator.validate(event).isEmpty());
    }

    @Test
    void event_nullMetadataValue_isTolerated() {
        EventModel event = validEvent();
        Map<String, String> metadata = new HashMap<>();
        metadata.put("k", null);
        event.setMetadata(metadata);
        assertTrue(validator.validate(event).isEmpty());
    }

    @Test
    void event_emptyMetadata_isAccepted() {
        EventModel event = validEvent();
        event.setMetadata(new HashMap<>());
        assertTrue(validator.validate(event).isEmpty());
    }

    private static Map<String, String> metadataWithEntries(int count) {
        Map<String, String> metadata = new HashMap<>();
        IntStream.range(0, count).forEach(i -> metadata.put("key_" + i, "value"));
        return metadata;
    }

    // ---- labels and related resources --------------------------------------------------------

    @Test
    void resource_tooManyLabels_isRejected() {
        Resource resource = validResource();
        resource.setLabels(IntStream.range(0, FieldLimits.LABELS_MAX + 1)
                .mapToObj(i -> "label_" + i)
                .collect(Collectors.toCollection(ArrayList::new)));
        assertTrue(paths(validator.validate(resource)).contains("labels"));
    }

    @Test
    void resource_labelOverMaxLength_isRejected() {
        Resource resource = validResource();
        resource.setLabels(new ArrayList<>(List.of(repeat(FieldLimits.LABEL_LENGTH_MAX + 1))));
        assertFalse(validator.validate(resource).isEmpty());
    }

    @Test
    void event_tooManyRelatedResources_isRejected() {
        EventModel event = validEvent();
        event.setRelatedResources(IntStream.range(0, FieldLimits.RELATED_RESOURCES_MAX + 1)
                .mapToObj(i -> new ai.intellistream.datahub.models.IdCollection())
                .collect(Collectors.toCollection(ArrayList::new)));
        assertTrue(paths(validator.validate(event)).contains("relatedResources"));
    }

    // ---- geolocation -------------------------------------------------------------------------

    @Test
    void geoLocation_overMaxLength_isRejected() {
        // Structurally valid GeoJSON, just far too much of it.
        String coordinates = IntStream.range(0, 20_000)
                .mapToObj(i -> "[1.0,2.0]")
                .collect(Collectors.joining(","));
        GeoLocation geo = new GeoLocation("{\"type\":\"MultiPoint\",\"coordinates\":[" + coordinates + "]}");
        assertTrue(geo.isValidGeoJson(), "precondition: the payload is valid GeoJSON");
        assertTrue(paths(validator.validate(geo)).contains("json"));
    }

    // ---- datapoints --------------------------------------------------------------------------

    @Test
    void datapointValueOverMax_isRejected() {
        DatapointString datapoint = new DatapointString("1735689600000", repeat(FieldLimits.DATAPOINT_VALUE_MAX + 1));
        assertTrue(paths(validator.validate(datapoint)).contains("value"));
    }

    @Test
    void datapointConstraintsCascadeThroughTheCollection() {
        // Regression: DatapointsCollection.datapoints carried no @Valid, so neither @NotBlank nor the
        // value cap on DatapointString was ever evaluated on an insert.
        DatapointsCollection collection = new DatapointsCollection();
        collection.setExternalId("a_timeseries");
        collection.setDatapoints(List.of(new DatapointString("1735689600000", repeat(FieldLimits.DATAPOINT_VALUE_MAX + 1))));

        DataWrapper<DatapointsCollection> wrapper = new DataWrapper<>();
        wrapper.setItems(List.of(collection));

        assertFalse(validator.validate(wrapper).isEmpty());
    }

    @Test
    void tooManyDatapointsInOneCollection_isRejected() {
        DatapointsCollection collection = new DatapointsCollection();
        collection.setExternalId("a_timeseries");
        collection.setDatapoints(IntStream.range(0, FieldLimits.DATAPOINTS_PER_COLLECTION_MAX + 1)
                .mapToObj(i -> new DatapointString("1735689600000", "1.0"))
                .toList());
        assertTrue(paths(validator.validate(collection)).contains("datapoints"));
    }

    // ---- batch size --------------------------------------------------------------------------

    @Test
    void batchAtMaxItems_isAccepted() {
        DataWrapper<EventModel> wrapper = new DataWrapper<>();
        wrapper.setItems(IntStream.range(0, FieldLimits.BATCH_ITEMS_MAX)
                .mapToObj(i -> {
                    EventModel event = validEvent();
                    event.setExternalId("event_external_id_" + i);
                    return event;
                })
                .toList());
        assertTrue(validator.validate(wrapper).isEmpty());
    }

    @Test
    void batchOverMaxItems_isRejected() {
        DataWrapper<EventModel> wrapper = new DataWrapper<>();
        wrapper.setItems(IntStream.range(0, FieldLimits.BATCH_ITEMS_MAX + 1)
                .mapToObj(i -> validEvent())
                .toList());
        assertEquals(Set.of("items"), paths(validator.validate(wrapper)));
    }
}
