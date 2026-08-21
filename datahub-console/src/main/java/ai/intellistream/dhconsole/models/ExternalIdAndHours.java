// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.dhconsole.models;

import lombok.Data;

import java.time.ZonedDateTime;

@Data
public class ExternalIdAndHours{

    private String externalId;
    private Integer hoursAgo;
    private Boolean raw = false;
    private ZonedDateTime startTime = null;
    private ZonedDateTime endTime = null;
    /**
     * Aggregation bucket size for the datapoint query (e.g. "5 min", "6 hour",
     * "1 day"). When null/blank the service falls back to a default. Lets the
     * UI cap the number of returned points per request.
     */
    private String granularity = null;

}
