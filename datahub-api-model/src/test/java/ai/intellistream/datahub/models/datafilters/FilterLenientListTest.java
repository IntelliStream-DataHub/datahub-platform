// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.models.datafilters;

import ai.intellistream.datahub.models.events.EventFilter;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A bare value is accepted wherever a list is declared, so the plural fields do not tax the common
 * call. {@code "source": "sap"} and {@code "source": ["sap"]} mean the same thing.
 *
 * <p>Checked through a plain {@link JsonMapper} with no configuration, because that is the point:
 * the leniency rides on the DTO via {@code @SingleOrList}, so the Java SDK and anything else
 * consuming this module gets it without configuring a mapper of its own.
 */
class FilterLenientListTest {

    private final JsonMapper mapper = JsonMapper.builder().build();

    @Test
    void aBareStringIsAcceptedWhereAListIsDeclared() {
        DataSetFilter filter = mapper.readValue(
                "{\"externalId\":\"sap_work_orders\",\"name\":\"SAP*\",\"source\":\"sap\",\"labels\":\"PUMP\"}",
                DataSetFilter.class);

        assertEquals(List.of("sap_work_orders"), filter.getExternalId());
        assertEquals(List.of("SAP*"), filter.getName());
        assertEquals(List.of("sap"), filter.getSource());
        assertEquals(List.of("PUMP"), filter.getLabels());
    }

    @Test
    void aBareNumberIsAcceptedForIds() {
        // Ids are strings on the wire; the single form has to survive both conversions at once.
        ResourceFilter filter = mapper.readValue("{\"id\":\"12\"}", ResourceFilter.class);

        assertEquals(List.of(12L), filter.getId());
    }

    @Test
    void aBareObjectIsAcceptedForDataSetIds() {
        ResourceFilter filter = mapper.readValue("{\"dataSetId\":{\"externalId\":\"data_set_sap\"}}",
                ResourceFilter.class);

        assertEquals(1, filter.getDataSetId().size());
        assertEquals("data_set_sap", filter.getDataSetId().getFirst().getExternalId());
    }

    @Test
    void theSingleAndListFormsProduceTheSameFilter() {
        TimeseriesFilter single = mapper.readValue("{\"unit\":\"bar\",\"valueType\":\"FLOAT\"}", TimeseriesFilter.class);
        TimeseriesFilter list = mapper.readValue("{\"unit\":[\"bar\"],\"valueType\":[\"FLOAT\"]}", TimeseriesFilter.class);

        assertEquals(list, single);
    }

    @Test
    void eventPatternListsAreLenientToo() {
        EventFilter filter = mapper.readValue(
                "{\"type\":\"Alarm\",\"subType\":\"Electrical\",\"status\":\"OPEN\",\"externalId\":\"work_order_*\"}",
                EventFilter.class);

        assertEquals(List.of("Alarm"), filter.getType());
        assertEquals(List.of("Electrical"), filter.getSubType());
        assertEquals(List.of("OPEN"), filter.getStatus());
        assertEquals(List.of("work_order_*"), filter.getExternalId());
    }

    /** Leniency must not blur the one distinction that carries meaning. */
    @Test
    void nullAndEmptyStillDifferOnDataSetIds() {
        assertNull(mapper.readValue("{}", ResourceFilter.class).getDataSetId());
        assertTrue(mapper.readValue("{\"dataSetId\":[]}", ResourceFilter.class).getDataSetId().isEmpty());
    }
}
