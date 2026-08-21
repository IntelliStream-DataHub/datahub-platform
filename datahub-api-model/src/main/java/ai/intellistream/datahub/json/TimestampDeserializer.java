// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.json;

import ai.intellistream.datahub.helpers.datetime.DateTimeHandler;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;

import java.time.ZonedDateTime;

/**
 * Parses a timestamp sent by a client as either an ISO-8601 string (with time
 * zone / offset) or an epoch value. Epoch values are always interpreted as UTC
 * milliseconds; ISO strings keep their own zone/offset.
 */
public class TimestampDeserializer extends ValueDeserializer<ZonedDateTime> {

    @Override
    public ZonedDateTime deserialize(JsonParser jsonParser, DeserializationContext ctxt) {
        String value = jsonParser.getText();
        if (value == null || value.isBlank()) {
            return null;
        }
        if (isEpoch(value)) {
            return DateTimeHandler.fromEpochUTCTimeAsZonedDateTime(Long.parseLong(value));
        }
        return ZonedDateTime.parse(value);
    }

    /** True when the value looks like an epoch number (all digits, optional leading '-'). */
    private boolean isEpoch(String value) {
        int length = value.length();
        // Epoch timestamps are typically 10 (seconds) to 13 (milliseconds) digits,
        // possibly negative; anything else is treated as an ISO-8601 string.
        if (length < 10 || length > 14) {
            return false;
        }
        char first = value.charAt(0);
        if (first != '-' && !Character.isDigit(first)) {
            return false;
        }
        for (int i = 1; i < length; i++) {
            if (!Character.isDigit(value.charAt(i))) {
                return false;
            }
        }
        return true;
    }
}
