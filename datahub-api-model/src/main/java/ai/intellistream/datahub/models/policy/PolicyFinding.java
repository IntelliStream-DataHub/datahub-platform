// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.models.policy;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * One item's verdict from one policy.
 *
 * @param index            the item's position in the request batch. Carried so an error can name
 *                         <em>which</em> of 500 submitted items was wrong; without it a caller with
 *                         a large batch is told only that something, somewhere, failed
 * @param externalId       the offending value, verbatim as submitted
 * @param decision         see {@link PolicyDecision}
 * @param policyExternalId the policy that fired, so the error names the rule rather than just the symptom
 * @param message          human-readable statement of what is wrong
 * @param suggestion       a conforming alternative where one can be derived, otherwise null. Not a
 *                         rewrite — the platform stopped silently rewriting external ids, which is
 *                         what this whole change is about. It is offered for the caller to accept
 */
public record PolicyFinding(
        int index,
        String externalId,
        PolicyDecision decision,

        // Serialized as "policy", matching PolicyWarning and the RFC 9457 violations[] entries. All
        // three describe the same thing to the same reader, so they must not have three names for
        // it. The Java name stays explicit about what the value is.
        @JsonProperty("policy") String policyExternalId,

        String message,
        String suggestion) {

    public boolean isRejection() {
        return decision == PolicyDecision.NOT_OK;
    }

    public boolean isWarning() {
        return decision == PolicyDecision.WARNING;
    }
}
