// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.flow.flows;

import ai.intellistream.datahub.flow.api.Processor;
import ai.intellistream.datahub.flow.processors.Clip;
import ai.intellistream.datahub.flow.processors.Despike;
import ai.intellistream.datahub.flow.processors.EventsSink;
import ai.intellistream.datahub.flow.processors.FilterRange;
import ai.intellistream.datahub.flow.processors.Interpolate;
import ai.intellistream.datahub.flow.processors.Resample;
import ai.intellistream.datahub.flow.processors.RouteOnAttribute;
import ai.intellistream.datahub.flow.processors.Scale;
import ai.intellistream.datahub.flow.processors.Smooth;
import ai.intellistream.datahub.flow.processors.Threshold;
import ai.intellistream.datahub.flow.processors.TimeseriesSink;
import ai.intellistream.datahub.flow.processors.TimeseriesSource;

import java.util.List;

/** The plan's example flow, with the built-in processors. */
final class TestFlows {

    static final String EXAMPLE = """
            {
              "schemaVersion": 1,
              "externalId": "temp_hourly",
              "dataSet": "ds_demo",
              "name": "Hourly temperature",
              "parameters": { "limit": { "type": "number", "default": 140 } },
              "processors": [
                { "id": "src",  "type": "datahub.timeseries.source",
                  "properties": { "timeseries": ["ts_out_temp", "ts_in_temp"] } },
                { "id": "f",    "type": "timeseries.scale",
                  "properties": { "factor": 1.8, "offset": 32 } },
                { "id": "out",  "type": "datahub.timeseries.sink",
                  "properties": { "target": "${timeseries.externalId}_f", "create": true,
                                  "name": "${timeseries.externalId} in °F", "unit": "°F" } },
                { "id": "over", "type": "timeseries.threshold",
                  "properties": { "threshold": "${parameters.limit}", "minDuration": "PT5M" },
                  "terminate": ["success"] },
                { "id": "ev",   "type": "datahub.events.sink",
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

    static List<Processor> builtIns() {
        return List.of(new TimeseriesSource(), new TimeseriesSink(), new EventsSink(), new Scale(),
                new Smooth(), new Despike(), new Interpolate(), new Clip(), new Resample(),
                new FilterRange(), new Threshold(), new RouteOnAttribute());
    }

    private TestFlows() {
    }
}
