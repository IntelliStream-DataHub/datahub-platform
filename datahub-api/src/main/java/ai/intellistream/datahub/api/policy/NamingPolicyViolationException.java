// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.policy;

import ai.intellistream.datahub.api.controllers.errors.FieldErrors;
import ai.intellistream.datahub.api.controllers.errors.BadRequestException;
import ai.intellistream.datahub.models.policy.PolicyFinding;

import java.util.List;

/**
 * Thrown when a batch contains external ids a naming policy rejects. Nothing has been written.
 *
 * <p>Extends {@link BadRequestException} so it maps to 400 through machinery that already exists.
 * 400 rather than 403 deliberately: this is malformed input measured against a rule, not an access
 * decision. A caller who gets 403 goes looking for a missing permission.
 *
 * <p>Carries <em>every</em> finding, not the first. Validation runs over the whole batch before
 * anything is written precisely so that a caller submitting 500 items learns about all of their
 * mistakes in one response rather than fixing them one redeploy at a time.
 */
public class NamingPolicyViolationException extends BadRequestException {

    /** The {@code type} URI on the RFC 9457 problem response. */
    private final transient List<PolicyFinding> violations;
    private final int batchSize;

    public NamingPolicyViolationException(List<PolicyFinding> violations, int batchSize) {
        super(summary(violations, batchSize), fieldsOf(violations));
        this.violations = List.copyOf(violations);
        this.batchSize = batchSize;
    }

    public List<PolicyFinding> getViolations() {
        return violations;
    }

    public int getBatchSize() {
        return batchSize;
    }

    /**
     * The human-readable summary.
     *
     * <p>It states that nothing was created, because that is the first thing a caller needs to know
     * — a partial write is the outcome they would otherwise have to go and check for. It also names
     * the policy, so the answer to "why" does not require a second request.
     */
    public String detail() {
        return summary(violations, batchSize);
    }

    private static String summary(List<PolicyFinding> violations, int batchSize) {
        String policy = violations.isEmpty() ? "the naming policy" : "'" + violations.getFirst().policyExternalId() + "'";
        return violations.size() + " of " + batchSize + " external ids violate naming policy "
                + policy + ". Nothing was created.";
    }

    /**
     * One entry per violation, keyed by the offending external id so the caller can pair a finding
     * with the item they sent. The index, the policy and the reason travel in the message, because
     * {@code fields} is a flat field-to-message list and splitting one finding across four entries
     * would leave the caller reassembling them.
     */
    private static FieldErrors fieldsOf(List<PolicyFinding> violations) {
        var fields = new FieldErrors();
        for (PolicyFinding violation : violations) {
            String reason = "item " + violation.index() + ": " + violation.message()
                    + " (policy '" + violation.policyExternalId() + "')";
            // A suggestion is legitimately absent for the pattern preset — there is no general way
            // to derive a string satisfying an arbitrary regex.
            if (violation.suggestion() != null) {
                reason = reason + ". Try '" + violation.suggestion() + "'";
            }
            fields.addFieldError(String.valueOf(violation.externalId()), reason);
        }
        return fields;
    }
}
