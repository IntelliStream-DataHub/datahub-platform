// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.services;

import ai.intellistream.datahub.jpa.dto.DatapointBigIntDTO;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.ZonedDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Guards that a bare {@link JsonMapper} (as ValkeyService uses for the cursor round-trip) can read a
 * stored datapoint back into a DTO whose timestamp is a {@link ZonedDateTime} — i.e. java-time
 * deserialization works without extra module wiring.
 */
class DatapointJsonRoundTripTest {

    @Test
    void bigIntDatapointWithZonedTimestampRoundTrips() {
        JsonMapper mapper = JsonMapper.builder().build();
        String stored = "{\"timestamp\":\"2026-01-01T00:00:00Z\",\"value\":7}";

        DatapointBigIntDTO dp = mapper.readValue(stored, DatapointBigIntDTO.class);

        assertEquals(7L, dp.getValue());
        assertEquals(ZonedDateTime.parse("2026-01-01T00:00:00Z"), dp.getTimestamp());
    }
}
