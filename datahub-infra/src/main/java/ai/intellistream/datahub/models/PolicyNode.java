// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.models;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.ZonedDateTime;
import java.util.Map;

@Data
@Schema(description = "Represents a Policy Node and its metadata configuration.")
public class PolicyNode {

    @Schema(description = "Node ID of the policy")
    private Long id;

    @Schema(description = "Display name of the policy node")
    private String name;

    @Schema(description = "External ID for this policy node")
    private String externalId;

    @Schema(description = "Node type (always POLICY)")
    private String nodeType;

    @Schema(description = "Metadata key/value pairs")
    private Map<String, String> metadata;

    @Schema(description = "Date created")
    private ZonedDateTime createdAt;

    @Schema(description = "Date last updated")
    private ZonedDateTime lastUpdated;
}
