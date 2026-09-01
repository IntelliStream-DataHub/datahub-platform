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
import static org.junit.jupiter.api.Assertions.assertFalse;
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
     * A node type that has no {@code isRoot} refuses a body carrying one, rather than binding it
     * and throwing it away.
     *
     * <p>The shared base briefly declared a setter so those bodies would bind — the flat create
     * shape sent {@code isRoot} on every body regardless of type-label, and the strict reader
     * would otherwise 400 them. That shape has since been retired, nothing in the repo sends the
     * field to a type that lacks it, and the hook had put back on the base exactly the field the
     * DTO split exists to keep off it. So the answer is the honest one: the field is not part of
     * this type, and the reader says so.
     */
    @Test
    void aTypeThatHasNoIsRootRejectsABodyCarryingOne() {
        for (String label : new String[]{"FUNCTION", "DATASET", "POLICY", "TIMESERIES"}) {
            UnknownFieldCollector.begin();
            try {
                strictMapper.readValue(
                        "{\"externalId\":\"n1\",\"name\":\"N1\",\"labels\":[\"" + label + "\"],"
                                + "\"isRoot\":false,\"metadata\":{}}", NodeModel.class);
                var unknown = UnknownFieldCollector.drain();
                assertFalse(unknown.isEmpty(),
                        label + " must report isRoot as unknown, so the api answers 400 rather than "
                                + "accepting a field this type has no concept of");
            } finally {
                UnknownFieldCollector.drain();
            }
        }
    }

    /** The types that can be roots still bind it normally. */
    @Test
    void aTypeThatCanBeRootStillBindsIt() {
        UnknownFieldCollector.begin();
        try {
            NodeModel bound = strictMapper.readValue("""
                    {"externalId":"pump_1","name":"Pump 1","labels":["PIPE"],"isRoot":true}
                    """, NodeModel.class);
            assertTrue(UnknownFieldCollector.drain().isEmpty());
            assertEquals(Boolean.TRUE, ((Resource) bound).getIsRoot());
        } finally {
            UnknownFieldCollector.drain();
        }
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
