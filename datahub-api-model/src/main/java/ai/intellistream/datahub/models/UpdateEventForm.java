// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.models;

import ai.intellistream.datahub.helpers.text.TextValidator;
import ai.intellistream.datahub.models.validation.EventFields;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Getter
@NoArgsConstructor
@Schema(name = "Update Event Form", description = "Update Event Form. One of external id or id fields are required.")
@JsonPropertyOrder({ "id", "externalId", "update" })
public class UpdateEventForm {

    // The external-id charset floor (see TextValidator.validateExternalIdCharset). Was
    // snake_case-only; relaxed so industrial tags (COM-99-PT-1034, =K1-M3+B02) survive
    // verbatim. A stricter house convention is now a configurable naming policy, not a
    // hard-coded bean constraint.
    @Pattern(regexp = "[A-Za-z0-9._:+=-]+")
    @Size(min = 3, max = 256)
    @Schema(description = "The external id of the event.", example = "work_order_sap_chemicals", requiredMode = Schema.RequiredMode.REQUIRED)
    private String externalId;

    @Schema(description = "The id of the event as uuid.", example = "db02f65e-3b77-4d5e-a8f6-4a2b5d2c8f19", requiredMode = Schema.RequiredMode.REQUIRED)
    private UUID id;

    EventFields update = new EventFields();

    public UpdateEventForm setExternalId(String externalId) {
        this.externalId = externalId;
        return this;
    }

    public UpdateEventForm setId(UUID id) {
        this.id = id;
        return this;
    }

    public UpdateEventForm setUpdate(EventFields update) {
        this.update = update;
        return this;
    }
}
