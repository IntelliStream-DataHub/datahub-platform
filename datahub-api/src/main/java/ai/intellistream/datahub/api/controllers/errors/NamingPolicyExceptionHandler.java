// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.controllers.errors;

import ai.intellistream.datahub.api.policy.NamingPolicyViolationException;
import ai.intellistream.datahub.models.policy.PolicyFinding;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns a {@link NamingPolicyViolationException} into an RFC 9457 {@code application/problem+json}
 * 400, matching the shape {@code AccessDeniedExceptionHandler} already established.
 *
 * <p><strong>400, not 403.</strong> This is malformed input measured against a rule, not an access
 * decision — a caller who receives 403 goes looking for a permission they are missing, and there
 * isn't one. The fix is to change the external id, or to change the policy.
 *
 * <p>The {@code detail} states that nothing was created. That is the first thing a caller needs to
 * know on a batch write, because the alternative they have to assume otherwise is a partial write
 * they now have to go and reconcile.
 *
 * <p>Every violation is listed, not just the first. Validation deliberately runs over the whole
 * batch before anything is persisted so that one response can name all of them.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
@Slf4j
public class NamingPolicyExceptionHandler {

    @ExceptionHandler(NamingPolicyViolationException.class)
    public ProblemDetail handleNamingPolicyViolation(NamingPolicyViolationException ex) {
        log.debug("Naming policy rejected {} of {} external ids", ex.getViolations().size(), ex.getBatchSize());

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.detail());
        problem.setTitle("Bad Request");
        problem.setType(URI.create(NamingPolicyViolationException.PROBLEM_TYPE));
        problem.setProperty("violations", violationsOf(ex.getViolations()));
        return problem;
    }

    /**
     * Uses a {@link LinkedHashMap} rather than {@code Map.of} because {@code suggestion} is
     * legitimately null — there is no general way to derive a string satisfying an arbitrary regex,
     * so the {@code pattern} preset offers none — and {@code Map.of} rejects null values.
     */
    private static List<Map<String, Object>> violationsOf(List<PolicyFinding> violations) {
        List<Map<String, Object>> out = new ArrayList<>(violations.size());
        for (PolicyFinding violation : violations) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("index", violation.index());
            entry.put("externalId", violation.externalId());
            entry.put("policy", violation.policyExternalId());
            entry.put("reason", violation.message());
            if (violation.suggestion() != null) {
                entry.put("suggestion", violation.suggestion());
            }
            out.add(entry);
        }
        return out;
    }
}
