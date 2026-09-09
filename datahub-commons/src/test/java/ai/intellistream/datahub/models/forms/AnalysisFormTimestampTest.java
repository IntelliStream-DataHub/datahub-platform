// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.models.forms;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * POST /analysis takes the same timestamp forms as the rest of the API. Without the explicit
 * deserializer Jackson read a bare number as epoch seconds and nothing else, so milliseconds
 * landed far outside the window the caller meant.
 */
class AnalysisFormTimestampTest {

    private final JsonMapper mapper = JsonMapper.builder().build();

    private static final Instant T = Instant.parse("2024-06-17T12:34:56Z");

    private Instant start(String startValue) {
        String json = "{\"focusExternalId\":\"pump_a\",\"start\":" + startValue
                + ",\"end\":\"2024-06-18T00:00:00Z\"}";
        return mapper.readValue(json, AnalysisForm.class).getStart().toInstant();
    }

    @Test
    void theWindowTakesIsoAndEpochMillis() {
        assertEquals(T, start("\"2024-06-17T12:34:56Z\""));
        assertEquals(T, start("1718627696000"));
    }

    /** And refuses seconds, the same as every other timestamp the API takes. */
    @Test
    void theWindowRefusesEpochSeconds() {
        assertThrows(RuntimeException.class, () -> start("1718627696"));
    }
}
