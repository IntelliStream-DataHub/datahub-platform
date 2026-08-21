// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.policy;

import ai.intellistream.datahub.api.controllers.errors.BadRequestError;
import ai.intellistream.datahub.api.controllers.errors.BadRequestException;
import ai.intellistream.datahub.errors.ResponseError;
import ai.intellistream.datahub.models.policy.PolicyFinding;

import java.util.List;
import java.util.Map;

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
    public static final String PROBLEM_TYPE = "https://intellistream.ai/errors/naming-policy";

    private final transient List<PolicyFinding> violations;
    private final int batchSize;

    public NamingPolicyViolationException(List<PolicyFinding> violations, int batchSize) {
        super(buildError(violations, batchSize));
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
        String policy = violations.isEmpty() ? "the naming policy" : "'" + violations.getFirst().policyExternalId() + "'";
        return violations.size() + " of " + batchSize + " external ids violate naming policy "
                + policy + ". Nothing was created.";
    }

    private static ResponseError<BadRequestError> buildError(List<PolicyFinding> violations, int batchSize) {
        var error = new BadRequestError();
        error.setCode(400);
        String policy = violations.isEmpty() ? "the naming policy" : "'" + violations.getFirst().policyExternalId() + "'";
        error.setMessage(violations.size() + " of " + batchSize + " external ids violate naming policy "
                + policy + ". Nothing was created.");
        for (PolicyFinding violation : violations) {
            error.getFields().add(fieldsOf(violation));
        }
        return new ResponseError<BadRequestError>().setError(error);
    }

    private static Map<String, String> fieldsOf(PolicyFinding violation) {
        // Map.of rejects null values, and a suggestion is legitimately absent for the pattern preset
        // (there is no general way to derive a string satisfying an arbitrary regex).
        if (violation.suggestion() == null) {
            return Map.of(
                    "index", String.valueOf(violation.index()),
                    "externalId", String.valueOf(violation.externalId()),
                    "policy", String.valueOf(violation.policyExternalId()),
                    "reason", String.valueOf(violation.message()));
        }
        return Map.of(
                "index", String.valueOf(violation.index()),
                "externalId", String.valueOf(violation.externalId()),
                "policy", String.valueOf(violation.policyExternalId()),
                "reason", String.valueOf(violation.message()),
                "suggestion", violation.suggestion());
    }
}
