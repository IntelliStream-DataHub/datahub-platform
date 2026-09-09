// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.json;

import ai.intellistream.datahub.helpers.datetime.DateTimeHandler;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;

import java.time.ZonedDateTime;

/**
 * Parses a timestamp sent by a client as either an ISO-8601 string (with time
 * zone / offset) or an epoch value. Epoch values are UTC, read as seconds or as
 * milliseconds by magnitude ({@link DateTimeHandler#epochToMillis(long)}); ISO
 * strings keep their own zone/offset.
 */
public class TimestampDeserializer extends ValueDeserializer<ZonedDateTime> {

    @Override
    public ZonedDateTime deserialize(JsonParser jsonParser, DeserializationContext ctxt) {
        String value = jsonParser.getText();
        if (value == null || value.isBlank()) {
            return null;
        }
        if (isEpoch(value)) {
            long millis = DateTimeHandler.epochToMillis(Long.parseLong(value));
            return DateTimeHandler.fromEpochUTCTimeAsZonedDateTime(millis);
        }
        return ZonedDateTime.parse(value);
    }

    /** True when the value looks like an epoch number (all digits, optional leading '-'). */
    private boolean isEpoch(String value) {
        // Digits only, so a negative epoch gets the same range as a positive one. Epoch timestamps
        // are 10 (seconds) to 13 (milliseconds) digits; anything else is read as an ISO-8601
        // string, which leaves a 9-digit seconds value (before 2001-09-09) rejected on this path.
        int start = value.startsWith("-") ? 1 : 0;
        int digits = value.length() - start;
        if (digits < 10 || digits > 14) {
            return false;
        }
        for (int i = start; i < value.length(); i++) {
            if (!Character.isDigit(value.charAt(i))) {
                return false;
            }
        }
        return true;
    }
}
