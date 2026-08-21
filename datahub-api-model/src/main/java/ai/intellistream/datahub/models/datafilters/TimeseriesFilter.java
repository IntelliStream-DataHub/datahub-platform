// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.models.datafilters;

import ai.intellistream.datahub.json.SingleOrList;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;

/**
 * Filter criteria for {@code POST /timeseries/filter} and the {@code filter} of
 * {@code POST /timeseries/search}.
 *
 * <p>The inherited {@code dataSetId} takes ids or externalIds and expands the hierarchy. It carries
 * the name of a scalar {@code Long} field this filter used to declare — the one filter in the family
 * that could only be pointed at a single data set — but it is a list, and its entries are
 * {@code IdCollection} objects rather than bare numbers, so the old shape is rejected outright
 * instead of quietly meaning something new.
 *
 * <p>The {@code metadataKey}/{@code metadataValue} pair is gone. It existed only because the
 * inherited {@code metadata} map could not express "has this key, whatever its value"; the map now
 * reads a null value that way, so the pair had nothing left to say.
 */
@EqualsAndHashCode(callSuper = true)
@Data
@Schema(name = "Timeseries Query Filter", description = "Timeseries Query Filter Object")
public class TimeseriesFilter extends DataSetScopedFilter {

    /**
     * Timeseries whose unit matches any of these. Same pattern rules as the inherited
     * {@code name}: {@code *} and {@code %} are wildcards, everything else literal,
     * case-insensitive, entries OR together.
     */
    @SingleOrList
    @Size(max = 1000)
    @Schema(description = "Timeseries whose unit matches any of these patterns. `*` and `%` are wildcards.",
            example = "[\"kg/hr\", \"deg_*\"]")
    private List<String> unit;

    /**
     * Timeseries whose unit external id matches any of these — the catalogue id rather than the
     * display symbol. A pattern list on the same terms as {@link #unit}; it used to be a single
     * exact string, so naming two units took two calls.
     */
    @SingleOrList
    @Size(max = 1000)
    @Schema(description = "Timeseries whose unit external id matches any of these patterns.",
            example = "[\"mass_flow_rate_kghr\", \"temperature_*\"]")
    private List<String> unitExternalId;

    /**
     * Timeseries storing any of these value types — {@code BIGINT}, {@code FLOAT},
     * {@code FLOAT32}, {@code NUMERIC}, {@code DECIMAL32}, {@code TEXT} or {@code MIXED}.
     *
     * <p>Matched exactly, case-insensitively, and <em>not</em> as patterns: this is a closed
     * catalogue the platform ships, not caller-chosen text, so a wildcard over it would only ever
     * be a way to misspell one of seven known values. An entry naming no known type simply matches
     * nothing.
     */
    @SingleOrList
    @Size(max = 100)
    @Schema(description = "Timeseries storing any of these value types.", example = "[\"FLOAT\", \"BIGINT\"]")
    private List<String> valueType;
}
