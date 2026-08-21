// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.responses;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

/**
 * Home-dashboard live counts for a single tenant. Computed periodically by the API's
 * {@code StatsRefreshJob}, cached in the tenant's Valkey, and served by {@code StatsController}.
 *
 * <p>The four "Jump back in" tiles each render two of these counts (assets root/all,
 * time-series/datapoints, events/alarms, datasets/policies); {@link #topEventTypes} backs the
 * "this week" operational widget that replaced the asset-network graph.</p>
 */
@Getter
@Setter
@NoArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class StatsCounts {

    private long rootAssets;
    private long allAssets;
    private long timeseries;
    private long datapoints;
    private long events;
    private long alarms;
    private long datasets;
    private long policies;

    /** Most frequent event types over the last 7 days, largest first. */
    private List<EventTypeCount> topEventTypes = new ArrayList<>();

    /** When these counts were computed (epoch millis) — lets the UI show how fresh they are. */
    private long updatedAt;

    @Getter
    @Setter
    @NoArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class EventTypeCount {
        private String type;
        private long count;

        public EventTypeCount(String type, long count) {
            this.type = type;
            this.count = count;
        }
    }
}