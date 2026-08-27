// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.timeseries;
import com.fasterxml.jackson.annotation.JsonInclude;
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

    /**
     * Lower-cased, and {@code null} kept as "the source did not supply one".
     *
     * <p>It used to fold null into the default, which meant no source could say it did not know.
     * The graph is such a source: it stores neither the value type nor the table engine, so a
     * series read through {@code /resources/fetch-related} reported {@code float32} whatever it
     * actually was. The default for an ordinary create is unaffected — it comes from the field
     * initialiser, and Jackson never calls a setter for a property the body omits. An explicit
     * {@code "valueType": null} now fails {@link jakarta.validation.constraints.NotBlank} with a
     * clear message instead of being quietly replaced.
     */
    public void setValueType(String valueType) {
        this.valueType = (valueType == null) ? null : valueType.toLowerCase();
    }

}
