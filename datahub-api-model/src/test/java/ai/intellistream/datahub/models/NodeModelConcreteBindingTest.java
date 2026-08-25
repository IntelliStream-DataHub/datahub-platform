// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.models;

import ai.intellistream.datahub.timeseries.Timeseries;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins that a body bound against a <em>concrete</em> node DTO binds directly, with no polymorphic
 * machinery involved. The label-keyed deserializer planned for the abstract {@link NodeModel} base
 * (see NODE_READ_REFACTOR.md) must be registered for the base type only; every single-type
 * endpoint, SDK read, and create/update request path binds against a concrete class and must keep
 * working exactly as below — including when the body's labels name a <em>different</em> type,
 * because a concrete target states the type and label dispatch only exists where the target is
 * the base. This net has to stay green through every slice of the polymorphism work.
 */
class NodeModelConcreteBindingTest {

    private final JsonMapper mapper = JsonMapper.builder().build();

    @Test
    void resourceBindsAsConcreteTarget() {
        Resource r = mapper.readValue("""
                {"externalId":"pump_1","name":"Pump 1","labels":["PIPE"],"isRoot":true}
                """, Resource.class);

        assertEquals("pump_1", r.getExternalId());
        assertEquals("Pump 1", r.getName());
        assertEquals(List.of("PIPE"), r.getLabels());
        assertEquals(true, r.getIsRoot());
    }

    @Test
    void timeseriesBindsAsConcreteTarget() {
        Timeseries ts = mapper.readValue("""
                {"externalId":"engine_temp","name":"Engine Temp","unit":"Deg C","valueType":"FLOAT"}
                """, Timeseries.class);

        assertEquals("engine_temp", ts.getExternalId());
        assertEquals("Deg C", ts.getUnit());
        // The type-label is intrinsic: setLabels keeps it present even when the body has none.
        assertTrue(ts.getLabels().contains("TIMESERIES"));
    }

    @Test
    void dataSetModelBindsAsConcreteTarget() {
        DataSetModel ds = mapper.readValue("""
                {"externalId":"plant_a","name":"Plant A","description":"desc"}
                """, DataSetModel.class);

        assertEquals("plant_a", ds.getExternalId());
        assertTrue(ds.getLabels().contains("DATASET"));
    }

    @Test
    void policyBindsAsConcreteTarget() {
        Policy p = mapper.readValue("""
                {"externalId":"policy_x","name":"IS_WRITE_PROTECTED","type":"IS_WRITE_PROTECTED","value":"TRUE"}
                """, Policy.class);

        assertEquals("policy_x", p.getExternalId());
        assertEquals("TRUE", p.getValue());
        assertTrue(p.getLabels().contains("POLICY"));
    }

    /**
     * A concrete target wins over whatever the labels say: binding a DATASET-labelled body to
     * {@code Resource.class} yields a {@code Resource}. Catching a label/target mismatch is a
     * service-side validation concern, never Jackson's.
     */
    @Test
    void aTypeLabelOnAForeignConcreteTargetDoesNotRedirectBinding() {
        NodeModel bound = mapper.readValue("""
                {"externalId":"sneaky","name":"Sneaky","labels":["DATASET"]}
                """, Resource.class);

        assertInstanceOf(Resource.class, bound);
        assertTrue(bound.getLabels().contains("DATASET"));
    }
}
