// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.json;

import ai.intellistream.datahub.helpers.datetime.DateTimeHandler;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;

import java.time.ZonedDateTime;

/**
 * Binds {@link DateTimeHandler#parseClientTimestamp} to Jackson. The rule itself lives there, so a
 * field annotated with this accepts exactly what the non-JSON entry points do: ISO-8601 keeping its
 * own offset, or a UTC epoch in seconds or milliseconds.
 */
public class TimestampDeserializer extends ValueDeserializer<ZonedDateTime> {

    @Override
    public ZonedDateTime deserialize(JsonParser jsonParser, DeserializationContext ctxt) {
        String value = jsonParser.getText();
        // Blank stays null so @NotNull reports a missing field cleanly rather than a parse error.
        if (value == null || value.isBlank()) {
            return null;
        }
        return DateTimeHandler.parseClientTimestamp(value);
    }
}
