// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.models.events;

import ai.intellistream.datahub.models.IdCollection;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the wire shape of {@link EventFilter#getDataSetIds()}.
 *
 * <p>The field used to be a {@code List<IdObject>} — a one-field wrapper that could only carry an
 * id, while its own Swagger example advertised a payload ({@code [43, 234]}) that did not
 * deserialize at all. It is an {@code IdCollection} now, so a data set can be named by id or by
 * externalId.
 *
 * <p>The rest of these pin the three-way distinction between absent, null and empty, which is what
 * decides whether a query is unfiltered or matches nothing.
 */
class EventFilterWireContractTest {

    private final JsonMapper mapper = JsonMapper.builder().build();

    private List<IdCollection> readDataSetIds(String json) {
        return mapper.readValue(json, EventFilter.class).getDataSetId();
    }

    /** The ids in the list, with a null for any reference that named a data set instead. */
    private List<Long> idsOf(String json) {
        return readDataSetIds(json).stream().map(IdCollection::getId).toList();
    }

    /** The externalIds in the list, with a null for any reference that carried an id instead. */
    private List<String> externalIdsOf(String json) {
        return readDataSetIds(json).stream().map(IdCollection::getExternalId).toList();
    }

    @Test
    void acceptsIds() {
        assertEquals(List.of(43L, 234L), idsOf("{\"dataSetId\":[{\"id\":43},{\"id\":234}]}"));
    }

    @Test
    void acceptsExternalIds() {
        assertEquals(List.of("data_set_sap"),
                externalIdsOf("{\"dataSetId\":[{\"externalId\":\"data_set_sap\"}]}"));
    }

    @Test
    void acceptsIdsAndExternalIdsMixed() {
        List<IdCollection> refs = readDataSetIds(
                "{\"dataSetId\":[{\"id\":43},{\"externalId\":\"data_set_sap\"}]}");

        assertEquals(Arrays.asList(43L, null), refs.stream().map(IdCollection::getId).toList());
        assertEquals(Arrays.asList(null, "data_set_sap"),
                refs.stream().map(IdCollection::getExternalId).toList());
    }

    /**
     * The ingress guarantee: a browser holds ids as strings precisely because they exceed
     * {@code Number.MAX_SAFE_INTEGER}, so a string id past 2^53 must parse to the exact long.
     */
    @Test
    void parsesIdsBeyondJavaScriptSafeRange() {
        assertEquals(List.of(9007199254740993L), idsOf("{\"dataSetId\":[{\"id\":\"9007199254740993\"}]}"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void serializesIdsAsStrings() {
        EventFilter filter = new EventFilter();
        filter.setDataSetId(List.of(IdCollection.createFromId(43L),
                IdCollection.createFromId(9007199254740993L)));

        Map<String, Object> m = mapper.readValue(mapper.writeValueAsString(filter), Map.class);

        // IdCollection carries ToStringSerializer on its id, so they survive a JS round trip.
        assertEquals(List.of(Map.of("id", "43"), Map.of("id", "9007199254740993")), m.get("dataSetId"));
    }

    @Test
    void nullStaysNull() {
        assertNull(readDataSetIds("{\"dataSetId\":null}"));
    }

    @Test
    void absentStaysNull() {
        assertNull(readDataSetIds("{}"));
    }

    /**
     * An explicit empty list is NOT null: the caller asked to be narrowed to a set of data sets and
     * that set is empty, so the query must match nothing rather than everything.
     */
    @Test
    void emptyArrayStaysEmpty() {
        List<IdCollection> refs = readDataSetIds("{\"dataSetId\":[]}");
        assertNotNull(refs);
        assertTrue(refs.isEmpty());
    }


    /**
     * The regression this whole change exists to fix. datahub-api's {@code JacksonConfig} registers
     * {@code Nulls.AS_EMPTY} for every Collection, which turned {@code "dataSetId": null} into an
     * empty list and therefore into {@code data_set_id IN []} — zero events, silently.
     *
     * <p>The override is rebuilt here rather than imported because api-model is framework-free and
     * cannot see datahub-api's config. Keep the two in step: if {@code JacksonConfig} changes how it
     * coerces collections, this test is what tells you whether the meaning of null changed with it.
     */
    @Test
    void nullSurvivesTheApisAsEmptyCollectionCoercion() {
        JsonMapper apiLikeMapper = JsonMapper.builder()
                .withConfigOverride(java.util.Collection.class, override ->
                        override.setNullHandling(JsonSetter.Value.forValueNulls(Nulls.AS_EMPTY)))
                .build();

        EventFilter filter = apiLikeMapper.readValue("{\"dataSetId\":null}", EventFilter.class);

        assertNull(filter.getDataSetId());
    }
}
