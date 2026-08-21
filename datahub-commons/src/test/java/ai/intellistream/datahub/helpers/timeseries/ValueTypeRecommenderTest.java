// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.helpers.timeseries;

import ai.intellistream.datahub.api.responses.ValueTypeRecommendation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ValueTypeRecommenderTest {

    @Test
    void smallExactUnitsRecommendDecimal32() {
        for (String id : new String[]{
                "temperature_deg_c", "temperature_deg_f", "pressure_bar",
                "concentration_percent", "concentration_ppm", "concentration_bq_m3",
                "electric_potential_volt", "current_ampere", "mass_kilogram",
                "volume_m3", "volume_flow_rate_litre", "energy_thermochemical_calorie"}) {
            ValueTypeRecommendation r = ValueTypeRecommender.recommend(id);
            assertEquals("DECIMAL32", r.getRecommendedValueType(), id + " should map to DECIMAL32");
            assertTrue(r.isRecognized(), id + " should be recognized");
        }
    }

    @Test
    void wideMagnitudeUnitsRecommendFloat32() {
        for (String id : new String[]{
                "pressure_pa", "power_watt", "energy_kw_hr", "energy_ev",
                "volume_barrel_pet_uk", "volume_barrel_pet_us"}) {
            ValueTypeRecommendation r = ValueTypeRecommender.recommend(id);
            assertEquals("FLOAT32", r.getRecommendedValueType(), id + " should map to FLOAT32");
            assertTrue(r.isRecognized(), id + " should be recognized");
        }
    }

    @Test
    void pascalDiffersFromBar() {
        // The headline distinction: bar fits Decimal32, pascals overflow it.
        assertEquals("DECIMAL32", ValueTypeRecommender.recommend("pressure_bar").getRecommendedValueType());
        assertEquals("FLOAT32", ValueTypeRecommender.recommend("pressure_pa").getRecommendedValueType());
    }

    @Test
    void lookupIsCaseInsensitiveAndTrimmed() {
        ValueTypeRecommendation r = ValueTypeRecommender.recommend("  Temperature_Deg_C ");
        assertEquals("DECIMAL32", r.getRecommendedValueType());
        assertTrue(r.isRecognized());
        // the original input is echoed back verbatim
        assertEquals("  Temperature_Deg_C ", r.getUnitExternalId());
    }

    @Test
    void unknownUnitFallsBackToCompactDefault() {
        ValueTypeRecommendation r = ValueTypeRecommender.recommend("totally_made_up_unit");
        assertEquals("FLOAT32", r.getRecommendedValueType());
        assertFalse(r.isRecognized());
        assertNotNull(r.getReason());
    }

    @Test
    void blankOrNullUnitFallsBackToDefault() {
        assertFalse(ValueTypeRecommender.recommend("").isRecognized());
        assertFalse(ValueTypeRecommender.recommend("   ").isRecognized());
        assertFalse(ValueTypeRecommender.recommend(null).isRecognized());
        assertEquals("FLOAT32", ValueTypeRecommender.recommend(null).getRecommendedValueType());
    }
}
