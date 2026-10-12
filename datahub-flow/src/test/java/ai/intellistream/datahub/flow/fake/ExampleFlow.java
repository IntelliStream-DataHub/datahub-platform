// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.flow.fake;

import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The plan's example flow, written against the {@link FakeProcessors}: read a series, scale it,
 * write it, and raise events where it goes over a limit — the same outcome feeding two processors.
 */
public final class ExampleFlow {

    public static final JsonMapper JSON = JsonMapper.builder().build();

    public static final String DEFINITION = """
            {
              "schemaVersion": 1,
              "externalId": "temp_hourly",
              "dataSet": "ds_demo",
              "name": "Hourly temperature: Fahrenheit series and over-limit alarms",
              "parameters": {
                "window": { "type": "duration", "default": "PT1H" },
                "limit":  { "type": "number",   "default": 35 }
              },
              "processors": [
                { "id": "src",  "type": "test.source",
                  "properties": { "series": ["ts_out_temp", "ts_in_temp"] } },
                { "id": "f",    "type": "test.scale",
                  "properties": { "factor": 1.8, "offset": 32 } },
                { "id": "out",  "type": "test.sink",
                  "properties": { "target": "${timeseries.externalId}_f" },
                  "retry": { "maxAttempts": 3, "backoff": "PT5S" } },
                { "id": "over", "type": "test.threshold",
                  "properties": { "threshold": "${parameters.limit}" },
                  "terminate": ["success"] },
                { "id": "ev",   "type": "test.events.sink",
                  "properties": { "type": "temperature", "series": "${timeseries.externalId}" } }
              ],
              "connections": [
                { "from": "src",  "relationship": "success", "to": "f" },
                { "from": "f",    "relationship": "success", "to": "out" },
                { "from": "f",    "relationship": "success", "to": "over" },
                { "from": "over", "relationship": "alarms",  "to": "ev" }
              ],
              "trigger":   { "type": "schedule", "cron": "0 0 * * * *", "timezone": "Europe/Oslo",
                             "window": "${parameters.window}", "delay": "PT5M" },
              "execution": { "maxConcurrentRuns": 1, "maxAttempts": 2, "timeout": "PT15M", "memory": "512MB" }
            }
            """;

    private ExampleFlow() {
    }

    /** A fresh, editable copy. */
    public static ObjectNode tree() {
        return (ObjectNode) JSON.readTree(DEFINITION);
    }
}
