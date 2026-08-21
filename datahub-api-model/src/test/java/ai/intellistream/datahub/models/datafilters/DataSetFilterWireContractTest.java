// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.models.datafilters;

import ai.intellistream.datahub.helpers.text.ExternalIds;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the wire shape of the identity selectors added to {@link DataSetFilter}.
 *
 * <p>These are the parts a published artifact cannot change later without breaking callers: that
 * ids survive a JavaScript client (strings out, either form in), that external ids are matched
 * through the derived hash rather than sent as one, and that the derived hash accessor does not
 * escape into the request contract as a field callers think they should populate.
 */
class DataSetFilterWireContractTest {

    private final JsonMapper mapper = JsonMapper.builder().build();

    private DataSetFilter read(String json) {
        return mapper.readValue(json, DataSetFilter.class);
    }

    @Test
    void idsAcceptStringsSoLargeIdsSurviveAJavaScriptClient() {
        assertEquals(List.of(12L, 18L), read("{\"id\":[\"12\",\"18\"]}").getId());
    }

    @Test
    void idsAlsoAcceptRawNumbers() {
        assertEquals(List.of(12L, 18L), read("{\"id\":[12,18]}").getId());
    }

    @Test
    void idsSerialiseAsStrings() {
        DataSetFilter filter = new DataSetFilter();
        filter.setId(List.of(9007199254740993L)); // beyond a double's integer precision

        String json = mapper.writeValueAsString(filter);

        assertTrue(json.contains("\"id\":[\"9007199254740993\"]"), json);
    }

    /** Absent means "no restriction"; the query must be able to tell that from an empty list. */
    @Test
    void absentListsStayNullRatherThanBecomingEmpty() {
        DataSetFilter filter = read("{}");

        assertNull(filter.getId());
        assertNull(filter.getExternalId());
        assertNull(filter.getName());
        assertNull(filter.getSource());
    }

    @Test
    void emptyListsSurviveAsEmptyRatherThanNull() {
        DataSetFilter filter = read("{\"id\":[],\"externalId\":[],\"name\":[]}");

        assertEquals(List.of(), filter.getId());
        assertEquals(List.of(), filter.getExternalId());
        assertEquals(List.of(), filter.getName());
    }

    @Test
    void externalIdHashesAreDerivedFromTheLowercasedForm() {
        DataSetFilter filter = read("{\"externalId\":[\"SAP_work_orders\"]}");

        assertEquals(List.of(ExternalIds.hash("sap_work_orders")), filter.getExternalIdHashes());
    }

    @Test
    void externalIdHashesAreNullWhenNoExternalIdsWereGiven() {
        assertNull(read("{}").getExternalIdHashes());
    }

    /** Derived, not sent — it must not appear as a field callers think they have to populate. */
    @Test
    void theDerivedHashAccessorStaysOffTheWire() {
        DataSetFilter filter = new DataSetFilter();
        filter.setExternalId(List.of("sap_work_orders"));

        String json = mapper.writeValueAsString(filter);

        assertFalse(json.contains("externalIdHashes"), json);
        assertTrue(json.contains("\"externalId\":[\"sap_work_orders\"]"), json);
    }

    @Test
    void namesAndSourcesRoundTrip() {
        DataSetFilter filter = read("{\"name\":[\"SAP%\",\"Plant A\"],\"source\":[\"sap\",\"opc_*\"]}");

        assertEquals(List.of("SAP%", "Plant A"), filter.getName());
        assertEquals(List.of("sap", "opc_*"), filter.getSource());
    }

    /**
     * Both wildcard spellings reach the query as {@code %}, and an underscore does not — external
     * ids are full of underscores, so a literal one must not silently become "any character".
     */
    @Test
    void wildcardsTranslateAndUnderscoresStayLiteral() {
        DataSetFilter filter = read("{\"externalId\":[\"sap_*\",\"plant_%\",\"exact_id\"]}");

        assertEquals(List.of("sap\\_%", "plant\\_%"), filter.getExternalIdPatterns());
        // The entry with no wildcard never becomes a pattern: it goes through the hashed column.
        assertEquals(1, filter.getExternalIdHashes().size());
    }
}
