// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.jpa.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(name = "PolicyCreateRequest", description = "Request model for creating a new Policy node.")
public class PolicyCreateRequest {
    @Schema(description = "Name of the policy node (e.g. SecurityPolicy)")
    private String name;

    @Schema(description = "Template ID reference for governance template")
    private Long templateId;

    @Schema(description = "User-defined external ID for this policy node")
    private String externalId;
}
