// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.subscription;
import tools.jackson.databind.annotation.JsonSerialize;
import ai.intellistream.datahub.json.ToStringSerializer;

import ai.intellistream.datahub.models.IdCollection;
import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

@Schema(name = "Subscription", description = "A subscription bound to one or more timeseries. Creates a Pulsar topic and subscription on persist.")
@Getter
@Setter
@JsonPropertyOrder({"id", "externalId", "name", "timeseries", "systemManaged", "dateCreated", "lastUpdated"})
public class Subscription {

    @Schema(description = "The id of the subscription.", example = "12345")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    @NotBlank
    @Size(min = 3, max = 256)
    @JsonAlias({"external_id"})
    @JsonProperty("externalId")
    @Schema(description = "The external id of the subscription. Also used as the Pulsar subscription name.",
            example = "boiler_room_readings_sub",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private String externalId;

    @NotBlank
    @Size(min = 3, max = 256)
    @Schema(description = "The display name of the subscription.",
            example = "Boiler Room Readings",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private String name;

    @NotEmpty(message = "At least one timeseries must be specified.")
    @Schema(description = "The timeseries this subscription is bound to. Each entry can specify " +
            "a timeseries id, external id, or both. At least one entry is required.",
            example = "[{\"id\": 29}, {\"externalId\": \"heater_2012_temp\"}]",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private List<IdCollection> timeseries = new ArrayList<>();

    @JsonAlias({"system_managed"})
    @JsonProperty("systemManaged")
    @Schema(description = "True when this subscription was auto-provisioned by the function-binding " +
            "lifecycle. Server-controlled — read-only on the wire. System-managed subscriptions are " +
            "hidden from /subscriptions/list by default and refuse manual deletes.",
            accessMode = Schema.AccessMode.READ_ONLY)
    private Boolean systemManaged;

    @JsonAlias({"date_created"})
    @JsonProperty("dateCreated")
    @Schema(description = "When the subscription was created. Server-assigned on create.")
    private OffsetDateTime dateCreated;

    @JsonAlias({"last_updated"})
    @JsonProperty("lastUpdated")
    @Schema(description = "When the subscription was last updated. Server-assigned.")
    private OffsetDateTime lastUpdated;
}
