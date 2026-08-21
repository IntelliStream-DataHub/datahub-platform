// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.models.forms;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One policy to update: which policy, and what to change about it. Mirrors {@code UpdateEventForm}
 * and {@code UpdateResourceForm} so every update endpoint reads the same way.
 *
 * <p>A policy is identified by {@code id} or {@code externalId}, like every other update endpoint.
 * This {@code externalId} says <em>which</em> policy to change; to change the external id itself,
 * set {@code update.externalId} — so renaming means naming the policy by its current external id
 * and setting the new one.
 */
@Getter
@Setter
@NoArgsConstructor
@Schema(name = "Update Policy Form",
        description = "Update Policy Form. One of the id or externalId fields is required.")
@JsonPropertyOrder({ "id", "externalId", "update" })
public class UpdatePolicyForm {

    @Schema(description = "The id of the policy to update. Supply this or externalId.", example = "5")
    private Long id;

    @Schema(description = "The external id of the policy to update. Supply this or id.",
            example = "policy_is_write_protected")
    private String externalId;

    /**
     * Initialised so an item that names a policy but no changes is a no-op rather than a null
     * dereference — the failure mode that made {@code /datasets/update} answer 500 to a valid
     * request. The service still guards, because a client can send an explicit {@code null}.
     */
    private PolicyFields update = new PolicyFields();

}
