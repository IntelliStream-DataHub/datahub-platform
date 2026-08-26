// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.timeseries;
import tools.jackson.databind.annotation.JsonSerialize;
import ai.intellistream.datahub.json.ToStringSerializer;

import ai.intellistream.datahub.helpers.text.TextValidator;
import ai.intellistream.datahub.models.NodeModel;
import ai.intellistream.datahub.models.validation.AllowedValueType;
import ai.intellistream.datahub.models.validation.ForbiddenValues;
import ai.intellistream.datahub.timeseries.enums.TableEngine;
import com.fasterxml.jackson.annotation.*;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

import java.util.*;

/**
 * JSON example:
 * {
 *   "items": [
 *     {
 *       "id": 1,
 *       "externalId": "string",
 *       "name": "string",
 *       "metadata": {
 *         "property1": "string",
 *         "property2": "string"
 *       },
 *       "unit": "string",
 *       "assetId": 1,

 *       "description": "string",
 *       "dataSetId": 1,
 *       "createdTime": '',
 *       "lastUpdatedTime": ''
 *     }
 *   ],
 *   "nextCursor": "string"
 * }
 */

@Schema(name="Timeseries", description="Timeseries description")
@Getter
@Setter
@JsonIgnoreProperties(value = { "createdTimeHR", "lastUpdatedTimeHR", "elementId" })
@JsonPropertyOrder({"id", "externalId", "name", "*"})
public class Timeseries extends NodeModel {

    @NotBlank(message = "timeseries.unit.not.blank")
    @Size(max = 64)
    @Schema(description = "The unit that the time series use.", example = "kg/hr")
    private String unit;

    @Size(min = 3, max = 256, message = "{unit.externalId.size}")
    @ForbiddenValues(message = "Forbidden value for external id.")
    @Schema(description = "The external id of the unit that the time series use.", example = "mass_flow_rate_kghr")
    private String unitExternalId;

    private String tableEngine = TableEngine.MERGETREE.name();

    /** Value type used when the caller doesn't specify one (or sends null). */
    public static final String DEFAULT_VALUE_TYPE = "float32";

    @NotBlank
    @AllowedValueType
    @Schema(description = "The value type of the time series. Can be one of BIGINT, FLOAT, FLOAT32 (default), NUMERIC, DECIMAL32, TEXT and MIXED. Choosing the right one can optimize processing speed and reduce costs.", example = "FLOAT")
    private String valueType = DEFAULT_VALUE_TYPE;

    @JsonCreator
    public Timeseries(){
        // Seed through the shared setter so the type-label is applied by NodeModel's one rule.
        // The DTO is then valid without the caller repeating the label (the /timeseries endpoints
        // send none), and a serialized Timeseries carries the label that routes it to the
        // timeseries path on /resources/create — while any labels a caller *does* send survive.
        setLabels(new ArrayList<>());
    }

    @Override
    protected String typeLabel() {
        return "TIMESERIES";
    }

    public void setUnitExternalId(String unitExternalId) {
        if (unitExternalId != null) {
            this.unitExternalId = TextValidator.toSnakeLowerCasedAllowStartWithDigits(unitExternalId);
        }
    }

    public void setValueType(String valueType) {
        // An unspecified value type (null) falls back to the default rather than NPE-ing in
        // .toLowerCase(); an explicit blank ("") is left for @NotBlank to reject as malformed.
        this.valueType = (valueType == null) ? DEFAULT_VALUE_TYPE : valueType.toLowerCase();
    }

}
