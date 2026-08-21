// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.api.responses;

import jakarta.validation.constraints.NotBlank;

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


public class DatapointNumber extends DefaultDatapoint {

    @NotBlank
    private long timestamp;

    @NotBlank
    private double value;

    public DatapointNumber(long timestamp, double value) {
        super(timestamp, value);
    }
}
