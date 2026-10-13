// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.flow.flows;

import ai.intellistream.datahub.flow.api.Processor;
import ai.intellistream.datahub.flow.processors.Clip;
import ai.intellistream.datahub.flow.processors.EventsSink;
import ai.intellistream.datahub.flow.processors.EventsSource;
import ai.intellistream.datahub.flow.processors.Threshold;
import ai.intellistream.datahub.flow.processors.TimeseriesSink;
import ai.intellistream.datahub.flow.processors.TimeseriesSource;

import java.util.List;

/** The plan's example flow, with the built-in processors. */
public final class TestFlows {

    public static final String EXAMPLE = """
            {
              "schemaVersion": 1,
              "externalId": "temp_hourly",
              "dataSet": "ds_demo",
              "name": "Hourly temperature",
              "parameters": { "limit": { "type": "number", "default": 140 } },
              "processors": [
                { "id": "src",  "type": "read-timeseries",
                  "properties": { "timeseries": ["ts_out_temp", "ts_in_temp"] } },
                { "id": "f",    "type": "clip",
                  "properties": { "min": -60, "max": 60 } },
                { "id": "out",  "type": "write-timeseries",
                  "properties": { "target": "${timeseries.externalId}_f", "create": true,
                                  "name": "${timeseries.externalId} in °F", "unit": "°F" } },
                { "id": "over", "type": "threshold",
                  "properties": { "threshold": "${parameters.limit}", "minDuration": "PT5M" },
                  "terminate": ["success"] },
                { "id": "ev",   "type": "write-events",
                  "properties": { "type": "temperature", "subType": "over-limit" } }
              ],
              "connections": [
                { "from": "src",  "relationship": "success", "to": "f" },
                { "from": "f",    "relationship": "success", "to": "out" },
                { "from": "f",    "relationship": "success", "to": "over" },
                { "from": "over", "relationship": "alarms",  "to": "ev" }
              ],
              "trigger": { "type": "manual" }
            }
            """;

    public static List<Processor> builtIns() {
        return List.of(new TimeseriesSource(), new TimeseriesSink(), new EventsSource(), new EventsSink(),
                new Clip(), new Threshold());
    }

    private TestFlows() {
    }
}
