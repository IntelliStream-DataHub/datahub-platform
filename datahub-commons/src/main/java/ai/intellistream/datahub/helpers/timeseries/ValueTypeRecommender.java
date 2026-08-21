// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.helpers.timeseries;

import ai.intellistream.datahub.api.responses.ValueTypeRecommendation;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Hard-coded "which value type should I use for this unit?" advice for the create-timeseries flow,
 * keyed by a unit's catalogue {@code externalId} (e.g. {@code temperature_deg_c}, {@code pressure_bar},
 * {@code pressure_pa}; see Flyway V5/V6 for the unit catalogue).
 *
 * <p>The goal is the <b>best ClickHouse compression ratio</b> that still represents the unit's typical
 * magnitude and precision faithfully. The trade-offs between the value types (see
 * {@code TimeseriesValueType} / clickhouse.sql) drive the choices:
 *
 * <ul>
 *   <li><b>DECIMAL32</b> — Decimal32(4), 4 bytes, T64 codec. Exact to 4 decimals but limited to
 *       magnitude ±99999.9999. The best ratio for small-range, low-precision measurements
 *       (temperature, bar, %, ppm, volts...).</li>
 *   <li><b>FLOAT32</b> — Float32, 4 bytes, Gorilla codec. Same 4-byte footprint but covers a huge
 *       magnitude range (~7 significant digits). Used when values overflow DECIMAL32's fixed range
 *       (pascals, watts, kWh, oil barrels...).</li>
 *   <li><b>FLOAT</b> (Float64) / <b>NUMERIC</b> (Decimal64(6)) — 8-byte fallbacks for wider range or
 *       exactness; not recommended here because they compress worse than the 4-byte options.</li>
 * </ul>
 *
 * <p>Lookup is case-insensitive. Unknown unit ids fall back to a compact {@link #DEFAULT}.
 */
public final class ValueTypeRecommender {

    private ValueTypeRecommender() {}

    // Canonical value type names — must match TimeseriesValueType (datahub-infra) and the strings
    // accepted by TimeseriesValueType.getValueTypeId.
    private static final String FLOAT32 = "FLOAT32";
    private static final String DECIMAL32 = "DECIMAL32";

    private record Reco(String valueType, String reason) {}

    private static final Reco DEFAULT = new Reco(FLOAT32,
            "No unit-specific rule matched this externalId. Float32 is a safe, compact default for "
            + "analog measurements (4 bytes, Gorilla codec); choose DECIMAL32/NUMERIC for exact "
            + "decimals, BIGINT for whole-number counts, or TEXT for labels.");

    // Reasons grouped by storage decision so units that share a rationale share the text.
    private static final Reco SMALL_EXACT = new Reco(DECIMAL32,
            "Values stay within Decimal32(4)'s ±99999.9999 range and need only a few decimals. "
            + "Decimal32(4) stores them exactly in 4 bytes with the T64 codec — the best compression "
            + "of the numeric value types.");

    private static final Reco WIDE_ANALOG = new Reco(FLOAT32,
            "Values span a wide magnitude range that would overflow Decimal32(4)'s ±99999.9999 limit. "
            + "Float32 covers the full range in 4 bytes with the Gorilla codec — half the size of "
            + "Float64 and well-compressed for a slowly-changing signal.");

    private static final Map<String, Reco> BY_UNIT_EXTERNAL_ID = buildMap();

    /**
     * Recommend a value type for a unit identified by its catalogue {@code externalId}. Never returns
     * null; an unknown/blank id yields the generic {@link #DEFAULT} with {@code recognized = false}.
     */
    public static ValueTypeRecommendation recommend(String unitExternalId) {
        String key = normalize(unitExternalId);
        Reco reco = key == null ? null : BY_UNIT_EXTERNAL_ID.get(key);
        boolean recognized = reco != null;
        Reco chosen = recognized ? reco : DEFAULT;
        return new ValueTypeRecommendation(unitExternalId, chosen.valueType(), chosen.reason(), recognized);
    }

    private static String normalize(String unitExternalId) {
        if (unitExternalId == null) return null;
        String n = unitExternalId.trim().toLowerCase(Locale.ROOT);
        return n.isEmpty() ? null : n;
    }

    private static Map<String, Reco> buildMap() {
        Map<String, Reco> m = new HashMap<>();

        // Small range, low precision → exact and most compressible (Decimal32(4)).
        put(m, SMALL_EXACT,
                "temperature_deg_c",                // °C
                "temperature_deg_f",                // °F
                "pressure_bar",                     // bar (≤ ~1000)
                "concentration_bq_m3",              // Bq/m³ (radon etc.)
                "concentration_ppm",                // ppm
                "concentration_ppb",                // ppb
                "concentration_percent",            // %
                "area_m2",                          // m²
                "volume_m3",                        // m³
                "volume_flow_rate_m3",              // m³/h
                "volume_flow_rate_litre",           // L/h
                "mass_flow_rate_kghr",              // kg/h
                "mass_kilogram",                    // kg
                "electric_potential_volt",          // V
                "current_ampere",                   // A
                "energy_thermochemical_calorie",    // cal (bounded)
                "amounts_chemical_substance_mole"); // mol

        // Wide / large magnitude that overflows Decimal32(4) → Float32.
        put(m, WIDE_ANALOG,
                "pressure_pa",          // Pa (atmospheric ≈ 101 325)
                "power_watt",           // W (watts → megawatts)
                "energy_kw_hr",         // kWh (cumulative)
                "energy_ev",            // eV (very wide dynamic range)
                "volume_barrel_pet_uk", // oil barrels (large / cumulative)
                "volume_barrel_pet_us");

        return Map.copyOf(m);
    }

    private static void put(Map<String, Reco> m, Reco reco, String... unitExternalIds) {
        for (String id : unitExternalIds) {
            m.put(id, reco);
        }
    }
}
