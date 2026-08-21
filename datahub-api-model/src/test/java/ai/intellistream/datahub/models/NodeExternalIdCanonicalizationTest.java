// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.models;

import ai.intellistream.datahub.timeseries.Timeseries;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Every node DTO stores {@code externalId} verbatim — the rule lives once on {@link NodeModel}, so no
 * subclass can silently opt out.
 *
 * <p>This class previously pinned the opposite behaviour: {@code setExternalId} snake_cased the value,
 * so {@code Pump-1.A} read back as {@code pump_1_a}. That mutation is what broke byte-equality joins
 * against the systems that issue these identifiers, and it was lossy enough to collide —
 * {@code P-101} and {@code P.101} both landed on {@code p_101}, so "the source system's key is unique
 * by construction" stopped being true the moment we rewrote it. Case-insensitivity now lives in the
 * hash instead, where it can serve uniqueness and lookup without destroying what was sent.
 */
class NodeExternalIdCanonicalizationTest {

    @Test
    void resourceStoresExternalIdVerbatim() {
        Resource r = new Resource();
        r.setExternalId("Pump-1.A");
        assertEquals("Pump-1.A", r.getExternalId());
    }

    @Test
    void timeseriesStoresExternalIdVerbatim() {
        Timeseries t = new Timeseries();
        t.setExternalId("Engine.Temp");
        assertEquals("Engine.Temp", t.getExternalId());
    }

    @Test
    void dataSetModelStoresExternalIdVerbatim() {
        DataSetModel d = new DataSetModel();
        d.setExternalId("COM-99-PT-1034");
        assertEquals("COM-99-PT-1034", d.getExternalId());
    }

    @Test
    void industrialTagFormatsRoundTrip() {
        // The acceptance criteria from the task doc, at the DTO layer.
        Resource isa = new Resource();
        isa.setExternalId("COM-99-PT-1034");
        assertEquals("COM-99-PT-1034", isa.getExternalId());

        Resource iec81346 = new Resource();
        iec81346.setExternalId("=K1-M3+B02");
        assertEquals("=K1-M3+B02", iec81346.getExternalId());
    }

    @Test
    void existingSnakeCaseIdsAreUnaffected() {
        Resource r = new Resource();
        r.setExternalId("valve_pressure_sensors");
        assertEquals("valve_pressure_sensors", r.getExternalId());
    }
}
