// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.timeseries;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.ArrayList;
import java.util.Collection;


/**
 * JSON example:
 * {
 *   "items": [
 *     {
 *     "start": 0,
 *     "end": 0,
 *     "limit": 0,
 *     "aggregates":
 *     [
 *         "average"
 *     ],
 *     "granularity": "1h",
 *     "includeOutsidePoints": false,
 *     "cursor": "string",
 *     "id": 1
 *     }
 *   ]
 */

@Schema(name="DatapointChildRequest", description="Datapoint child request description")
public class DatapointChildRequest {


    @NotNull
    private Long id;

    @NotBlank
    private String externalId;

    private Long start = 0L;

    private Long end = 0L;

    private Long limit = 100L;

    private Collection<String> aggregates = new ArrayList<>();

    private String granularity;

    // Whether to include the last datapoint before the requested time period, and the first one after. This option can be useful for interpolating data.
    private Boolean includeOutsidePoints = false;

    private String cursor;

    public Long getId() {
        return id;
    }

    public DatapointChildRequest setId(Long id) {
        this.id = id;
        return this;
    }

    public String getExternalId() {
        return externalId;
    }

    public DatapointChildRequest setExternalId(String externalId) {
        this.externalId = externalId;
        return this;
    }

    public Long getStart() {
        return start;
    }

    public DatapointChildRequest setStart(Long start) {
        this.start = start;
        return this;
    }

    public Long getEnd() {
        return end;
    }

    public DatapointChildRequest setEnd(Long end) {
        this.end = end;
        return this;
    }

    public Long getLimit() {
        return limit;
    }

    public DatapointChildRequest setLimit(Long limit) {
        this.limit = limit;
        return this;
    }

    public Collection<String> getAggregates() {
        return aggregates;
    }

    public DatapointChildRequest setAggregates(Collection<String> aggregates) {
        this.aggregates = aggregates;
        return this;
    }

    public String getGranularity() {
        return granularity;
    }

    public DatapointChildRequest setGranularity(String granularity) {
        this.granularity = granularity;
        return this;
    }

    public Boolean getIncludeOutsidePoints() {
        return includeOutsidePoints;
    }

    public DatapointChildRequest setIncludeOutsidePoints(Boolean includeOutsidePoints) {
        this.includeOutsidePoints = includeOutsidePoints;
        return this;
    }

    public String getCursor() {
        return cursor;
    }

    public DatapointChildRequest setCursor(String cursor) {
        this.cursor = cursor;
        return this;
    }
}
