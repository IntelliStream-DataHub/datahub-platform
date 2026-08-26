// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.config;

import ai.intellistream.datahub.models.DataSetModel;
import ai.intellistream.datahub.models.NodeModel;
import ai.intellistream.datahub.models.NodeModelSubtypes;
import ai.intellistream.datahub.models.Resource;
import ai.intellistream.datahub.timeseries.Timeseries;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;

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
