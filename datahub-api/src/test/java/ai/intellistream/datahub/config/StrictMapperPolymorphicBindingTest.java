// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.config;

import ai.intellistream.datahub.models.DataSetModel;
import ai.intellistream.datahub.models.NodeModel;
import ai.intellistream.datahub.models.NodeModelSubtypes;
import ai.intellistream.datahub.models.Resource;
import ai.intellistream.datahub.timeseries.Timeseries;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The strict request-body converter does not use the shared mapper directly: it
 * {@code rebuild()}s it with an unknown-field collector attached ({@link StrictRequestBodyConfig}).
 * Every {@code @RequestBody} on the api is bound by that rebuilt mapper, so if a rebuild dropped
 * the registered modules, {@code POST /resources/create} would fail to type its node bodies at the
 * HTTP boundary while every unit test using a plain mapper stayed green. This pins that the
 * label-keyed dispatch survives the rebuild.
 */
class StrictMapperPolymorphicBindingTest {

    /** Mirrors StrictRequestBodyConfig: the Boot-configured mapper, rebuilt with the collector. */
    private final JsonMapper strictMapper = JsonMapper.builder()
            .addModule(new NodeModelSubtypes())
            .build()
            .rebuild()
            .addHandler(new UnknownFieldCollector())
            .build();

    /**
     * The regression this guards: the flat create shape this api has always accepted carries
     * {@code isRoot} on every body, even when its label sends it to a DTO that has no such field.
     * In-tree callers now send the node shapes directly, but clients built against the older
     * contract still post the flat one. Under the strict mapper an unknown
     * field is a 400, so before {@code NodeModel.setIsRoot} existed, creating a function, data
     * set, policy or time series through {@code /resources/create} answered 400 instead of 201.
     * A lenient mapper cannot catch this; only the strict one the api actually binds with can.
     */
    @Test
    void legacyBodiesCarryingIsRootStillBindForEveryType() {
        for (String label : new String[]{"FUNCTION", "DATASET", "POLICY", "TIMESERIES"}) {
            UnknownFieldCollector.begin();
            try {
                NodeModel bound = strictMapper.readValue(
                        "{\"externalId\":\"n1\",\"name\":\"N1\",\"labels\":[\"" + label + "\"],"
                                + "\"isRoot\":false,\"metadata\":{}}", NodeModel.class);
                assertTrue(UnknownFieldCollector.drain().isEmpty(),
                        label + " body must not report isRoot as an unknown field (the api 400s on those)");
                assertEquals(label, bound.getLabels().get(0));
            } finally {
                UnknownFieldCollector.drain();
            }
        }
    }

    /** Root-ness still applies where it is legal, and is discarded where it is not. */
    @Test
    void isRootIsAppliedOnResourcesAndDiscardedElsewhere() {
        Resource resource = (Resource) strictMapper.readValue(
                """
                {"externalId":"pump_1","name":"Pump 1","labels":["PIPE"],"isRoot":true}
                """, NodeModel.class);
        assertEquals(Boolean.TRUE, resource.getIsRoot());

        // A data set is never a navigation root; the value is accepted and dropped.
        assertInstanceOf(DataSetModel.class, strictMapper.readValue(
                """
                {"externalId":"plant_data","name":"Plant data","labels":["DATASET"],"isRoot":true}
                """, NodeModel.class));
    }

    @Test
    void theRebuiltStrictMapperStillDispatchesOnTheTypeLabel() {
        assertInstanceOf(Timeseries.class, strictMapper.readValue("""
                {"externalId":"engine_temp","name":"Engine Temp","labels":["TIMESERIES"],"unit":"Deg C"}
                """, NodeModel.class));
        assertInstanceOf(DataSetModel.class, strictMapper.readValue("""
                {"externalId":"plant_data","name":"Plant data","labels":["DATASET"]}
                """, NodeModel.class));
        assertInstanceOf(Resource.class, strictMapper.readValue("""
                {"externalId":"pump_1","name":"Pump 1","labels":["PIPE"]}
                """, NodeModel.class));
    }
}
