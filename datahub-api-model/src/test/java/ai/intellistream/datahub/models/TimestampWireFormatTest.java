// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.models;

import ai.intellistream.datahub.timeseries.Timeseries;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.databind.json.JsonMapper;

import java.time.ZonedDateTime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the actual on-the-wire format of the node DTOs' timestamps. The API disables
 * WRITE_DATES_AS_TIMESTAMPS (application.yml / McpResultConverter), so despite {@code AbstractResource}
 * storing the value as a {@code Long} epoch internally, it serializes through
 * {@code getCreatedTime(): ZonedDateTime} → an ISO-8601 string. This confirms all four node DTOs already
 * emit the *same* timestamp shape, which is what makes a future timestamp hoist wire-neutral.
 */
class TimestampWireFormatTest {

    // Same mapper config the app runs with (see McpResultConverter): ISO-8601 dates, not numeric.
    private final JsonMapper mapper = JsonMapper.builder()
            .disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS)
            .build();

    private static final ZonedDateTime T = ZonedDateTime.parse("2024-06-17T12:34:56Z");

    @Test
    void allNodeDtosSerializeCreatedTimeAsIsoString() {
        Resource resource = new Resource();
        resource.setCreatedTime(T);
        DataSetModel dataset = new DataSetModel();
        dataset.setCreatedTime(T);
        Timeseries timeseries = new Timeseries();
        timeseries.setCreatedTime(T);
        Policy policy = new Policy();
        policy.setCreatedTime(T);

        String rJson = mapper.writeValueAsString(resource);
        String dJson = mapper.writeValueAsString(dataset);
        String tJson = mapper.writeValueAsString(timeseries);
        String pJson = mapper.writeValueAsString(policy);

        System.out.println("Resource:   " + rJson);
        System.out.println("DataSet:    " + dJson);
        System.out.println("Timeseries: " + tJson);
        System.out.println("Policy:     " + pJson);

        // Not an epoch number — an ISO-8601 string, and identical across all four backing representations.
        String iso = "\"createdTime\":\"2024-06-17T12:34:56Z\"";
        assertTrue(rJson.contains(iso), "Resource: " + rJson);
        assertFalse(rJson.contains("\"createdTime\":1718"), "Resource emitted epoch, not ISO: " + rJson);
        assertTrue(dJson.contains(iso), "DataSet: " + dJson);
        assertTrue(tJson.contains(iso), "Timeseries: " + tJson);
        assertTrue(pJson.contains(iso), "Policy: " + pJson);
    }

    /**
     * {@code EventModel.eventTime} is a {@code Long} internally (epoch millis, for Avro), but its
     * {@code @JsonGetter} returns a {@code ZonedDateTime}, so the wire value is an ISO-8601 string —
     * matching the corrected {@code @Schema(type="string", format="date-time")}, not a numeric epoch.
     */
    @Test
    void eventModelSerializesEventTimeAsIsoString() {
        EventModel event = new EventModel();
        event.setEventTime(T);

        String json = mapper.writeValueAsString(event);
        System.out.println("Event: " + json);

        assertTrue(json.contains("\"eventTime\":\"2024-06-17T12:34:56Z\""),
                "eventTime must be an ISO-8601 string: " + json);
        assertFalse(json.contains("\"eventTime\":1718"),
                "eventTime emitted an epoch number, not ISO: " + json);
    }
}
