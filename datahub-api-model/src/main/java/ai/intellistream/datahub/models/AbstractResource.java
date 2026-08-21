// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.models;

import ai.intellistream.datahub.helpers.datetime.DateTimeHandler;
import com.fasterxml.jackson.annotation.JsonSetter;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;

import java.time.ZonedDateTime;
import java.util.Objects;

@Getter
public class AbstractResource {

    /**
     * When the object was created. Stored internally as a UTC epoch-millis {@code Long} because these DTOs
     * double as Avro Pulsar payloads and Avro's reflection doesn't handle {@link ZonedDateTime} — but it is
     * read and written through {@link #getCreatedTime()}/{@link #setCreatedTime(ZonedDateTime)}, so on the
     * JSON wire it is an ISO-8601 string (the app disables WRITE_DATES_AS_TIMESTAMPS).
     */
    @Schema(accessMode = Schema.AccessMode.READ_ONLY, description = "When the object was created, as an ISO-8601 UTC timestamp.", example = "2024-06-17T12:34:56Z")
    protected Long createdTime = DateTimeHandler.toEpochUTCTime(ZonedDateTime.now());

    /**
     * When the object was last updated. Same storage/serialization split as {@link #createdTime}: UTC
     * epoch-millis internally (Avro-safe), ISO-8601 string on the JSON wire.
     */
    @Schema(accessMode = Schema.AccessMode.READ_ONLY, description = "When the object was last updated, as an ISO-8601 UTC timestamp.", example = "2024-06-17T12:34:56Z")
    protected Long lastUpdatedTime = DateTimeHandler.toEpochUTCTime(ZonedDateTime.now()); // Epoch date time in UTC

    @JsonSetter("createdTime")
    public void setCreatedTime(ZonedDateTime dateTime) {
        this.createdTime = DateTimeHandler.toEpochUTCTime(Objects.requireNonNullElseGet(dateTime, ZonedDateTime::now));
    }

    @JsonSetter("lastUpdatedTime")
    public void setLastUpdatedTime(ZonedDateTime dateTime) {
        this.lastUpdatedTime = DateTimeHandler.toEpochUTCTime(Objects.requireNonNullElseGet(dateTime, ZonedDateTime::now));
    }

    public ZonedDateTime getCreatedTime() {
        return DateTimeHandler.fromEpochUTCTimeAsZonedDateTime(this.createdTime);
    }

    public ZonedDateTime getLastUpdatedTime() {
        return DateTimeHandler.fromEpochUTCTimeAsZonedDateTime(this.lastUpdatedTime);
    }

}
