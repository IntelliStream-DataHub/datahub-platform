// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.api.responses;

import ai.intellistream.datahub.models.validation.FieldLimits;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * JSON example:
 * {
 *   "datapoints": [
 *     {
 *       "timestamp": 1638795554528,
 *       "average": 0,
 *       "max": 0,
 *       "min": 0,
 *       "count": 0,
 *       "sum": 0,
 *       "interpolation": 0,
 *       "stepInterpolation": 0,
 *       "continuousVariance": 0,
 *       "discreteVariance": 0,
 *       "totalVariation": 0,
 *       "value"
 *     }
 *   ]
 */

@Data
@AllArgsConstructor
@NoArgsConstructor
@Schema(name="Data Point", description="Data Point with timestamp and value")
public class DatapointString {

    @NotNull
    @Schema(description = "The timestamp of the data point. Can be either ISO 8601 formatted \"2024-08-30T22:00:00Z\" or epoch time",
            example = "2024-08-30T22:00:00Z or 1723759200000",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private String timestamp;

    @NotBlank
    @Size(max = FieldLimits.DATAPOINT_VALUE_MAX)
    @Schema(description = """
            The data point value as a string, interpreted per the timeseries `valueType`:
            - `BIGINT` — whole number, max 8 bytes. No fractional part.
            - `FLOAT` — floating point, max 8 bytes. Fast to aggregate, but carries tiny \
            binary rounding error (it is a float, not an exact decimal).
            - `FLOAT32` — single-precision floating point, 4 bytes (~7 significant digits). \
            Same float rounding caveat as FLOAT; use when Float64's range/precision isn't needed.
            - `NUMERIC` — exact decimal, max 8 bytes: large magnitude range and 6 \
            fractional digits. Use when values must be exact.
            - `DECIMAL32` — compact exact decimal, 4 bytes (Decimal32(4)): every value is rounded \
            half-up to 4 decimal places, and a magnitude beyond ±99999.9999 is clamped to that \
            range and logged (the data point is kept, the batch is never rejected). Use NUMERIC if \
            larger magnitudes must be stored faithfully.
            - `TEXT` — arbitrary string (states, labels, modes, or non-numeric readings).
            - `MIXED` — a number or text per point, for sensors that emit both (e.g. readings plus a \
            `FAULT` status). Numbers are aggregated; text rows are skipped by aggregates.""",
            example = "344.544 or N/A or null",
            implementation = Object.class,
            requiredMode = Schema.RequiredMode.REQUIRED)
    private String value;


}
