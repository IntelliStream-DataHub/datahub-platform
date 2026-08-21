// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.timeseries;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.ArrayList;
import java.util.Collection;


/**
 * JSON example:
 * {
 *   "items": [
 *     {
 * "start": 0,
 * "end": 0,
 * "limit": 0,
 * "aggregates": [
 * "average"
 * ],
 * "granularity": "1h",
 * "includeOutsidePoints": false,
 * "cursor": "string",
 * "id": 1
 * }
 * ],
 * "start": 0,
 * "end": 0,
 * "limit": 100,
 * "aggregates": [
 * "average"
 * ],
 *  "granularity": "1h",
 *  "includeOutsidePoints": false,
 *  "ignoreUnknownIds": false
 * }
 */

@Schema(name="DatapointRequest", description="Datapoint request description")
public class DatapointRequest {

    private Long start = 0L;

    private Long end = 0L;

    private Long limit = 100L;

    private Collection<String> aggregates = new ArrayList<>();

    private String granularity;

    // Whether to include the last datapoint before the requested time period, and the first one after. This option can be useful for interpolating data.
    private Boolean includeOutsidePoints = false;

    // Ignore IDs and external IDs that are not found
    private Boolean ignoreUnknownIds = false;

    public Long getStart() {
        return start;
    }

    public DatapointRequest setStart(Long start) {
        this.start = start;
        return this;
    }

    public Long getEnd() {
        return end;
    }

    public DatapointRequest setEnd(Long end) {
        this.end = end;
        return this;
    }

    public Long getLimit() {
        return limit;
    }

    public DatapointRequest setLimit(Long limit) {
        this.limit = limit;
        return this;
    }

    public Collection<String> getAggregates() {
        return aggregates;
    }

    public DatapointRequest setAggregates(Collection<String> aggregates) {
        this.aggregates = aggregates;
        return this;
    }

    public String getGranularity() {
        return granularity;
    }

    public DatapointRequest setGranularity(String granularity) {
        this.granularity = granularity;
        return this;
    }

    public Boolean getIncludeOutsidePoints() {
        return includeOutsidePoints;
    }

    public DatapointRequest setIncludeOutsidePoints(Boolean includeOutsidePoints) {
        this.includeOutsidePoints = includeOutsidePoints;
        return this;
    }

    public Boolean getIgnoreUnknownIds() {
        return ignoreUnknownIds;
    }

    public DatapointRequest setIgnoreUnknownIds(Boolean ignoreUnknownIds) {
        this.ignoreUnknownIds = ignoreUnknownIds;
        return this;
    }
}
