// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.models.policy;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * A policy warning as it appears on a write response.
 *
 * <p>The response is how an integration learns that something it wrote is non-conforming; the
 * finding event ({@link PolicyFindingEvent}) is the durable record a steward's queue reads. Both
 * exist because they answer different questions — "what did my last call do" and "what is
 * outstanding".
 *
 * <p>The {@code warnings} array is <strong>absent when empty</strong>, so a response with nothing to
 * report is byte-identical to what clients see today.
 */
@Schema(name = "PolicyWarning", description = "A policy violation that was allowed through and recorded for review.")
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PolicyWarning(
        @Schema(description = "Position of the offending item in the submitted batch.", example = "3")
        int index,

        @Schema(description = "The external id that triggered the warning, exactly as submitted.", example = "Pump-A-01")
        String externalId,

        @Schema(description = "External id of the policy that fired.", example = "naming_snake_case")
        String policy,

        @Schema(description = "What is wrong.", example = "Does not match naming policy 'snake_case'.")
        String message,

        @Schema(description = "A conforming alternative, where one can be derived. Not applied — "
                + "external ids are stored exactly as sent.", example = "pump_a_01")
        String suggestion) {

    public static PolicyWarning from(PolicyFinding finding) {
        return new PolicyWarning(finding.index(), finding.externalId(), finding.policyExternalId(),
                finding.message(), finding.suggestion());
    }
}
