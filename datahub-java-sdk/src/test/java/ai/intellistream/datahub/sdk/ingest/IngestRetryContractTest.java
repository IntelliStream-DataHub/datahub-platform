// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.sdk.ingest;

import ai.intellistream.datahub.api.errors.Problem;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which failures are sent again, and who decides.
 *
 * <p>The status alone answers this wrongly in both directions. A 409 can be a duplicate external id
 * — never send that again — or a lost optimistic lock, which is resolved by doing exactly that. A
 * 5xx used to mean "blip, retry", but the API now separates a broker it could not publish to (503
 * {@code messaging-unavailable}, {@code same-request}) from a failure inside itself (500
 * {@code internal}, {@code needs-operator}). The {@code retry} member is the API's own answer, so
 * it decides where there is one.
 */
class IngestRetryContractTest {

    private static Problem problem(int status, String slug, String retry) {
        return Problem.of(status, "{\"type\":\"" + Problem.TYPE_BASE + slug + "\",\"status\":" + status
                + ",\"retry\":\"" + retry + "\"}");
    }

    @Test
    @DisplayName("retry: same-request is sent again whatever the status says")
    void aLostOptimisticLockIsRetriedAlthoughItIsA409() {
        assertTrue(IngestResult.isRetryable(409,
                problem(409, "optimistic-lock", Problem.RETRY_SAME_REQUEST)));
        assertTrue(IngestResult.isRetryable(503,
                problem(503, "messaging-unavailable", Problem.RETRY_SAME_REQUEST)));
    }

    @Test
    @DisplayName("retry: needs-operator stops the retries, although the status is a 5xx")
    void anInternalFailureIsNotRetried() {
        // Four more attempts only delay the failure the caller has to handle anyway.
        assertFalse(IngestResult.isRetryable(500,
                problem(500, "internal", Problem.RETRY_NEEDS_OPERATOR)));
        assertFalse(IngestResult.isRetryable(403,
                problem(403, "tenant-limit-reached", Problem.RETRY_NEEDS_OPERATOR)));
    }

    @Test
    @DisplayName("retry: change-request is terminal")
    void aCallerMistakeIsNotRetried() {
        assertFalse(IngestResult.isRetryable(400,
                problem(400, "validation-failed", Problem.RETRY_CHANGE_REQUEST)));
        assertFalse(IngestResult.isRetryable(422,
                problem(422, "invalid-timestamp", Problem.RETRY_CHANGE_REQUEST)));
    }

    @Test
    @DisplayName("with no problem document the status decides, as before")
    void theStatusIsTheFallback() {
        // A network error, a proxy's HTML 502, a gateway timeout — nothing to read a retry from.
        assertTrue(IngestResult.isRetryable(0, Problem.of(0, null)));
        assertTrue(IngestResult.isRetryable(429, Problem.of(429, null)));
        assertTrue(IngestResult.isRetryable(502, Problem.of(502, "<html>502</html>")));
        assertFalse(IngestResult.isRetryable(400, Problem.of(400, null)));
    }

    @Test
    @DisplayName("isTransientFailure follows the same rule the retries do")
    void transientFailureAgreesWithTheRetryLoop() {
        assertTrue(failedWith(409, problem(409, "optimistic-lock", Problem.RETRY_SAME_REQUEST))
                .isTransientFailure());
        assertFalse(failedWith(500, problem(500, "internal", Problem.RETRY_NEEDS_OPERATOR))
                .isTransientFailure());
    }

    @Test
    @DisplayName("what the spool holds is a separate question from what is retried now")
    void bufferingDoesNotFollowRetry() {
        // An expired token is change-request — that request will never work as it stands — yet
        // holding the data while someone renews the credential is exactly what the spool is for.
        IngestResult expiredToken = failedWith(401,
                problem(401, "unauthorized", Problem.RETRY_CHANGE_REQUEST));
        assertFalse(expiredToken.isTransientFailure());
        assertTrue(expiredToken.isBufferable());

        // And the converse: a lost optimistic lock is worth repeating at once, never worth spooling.
        IngestResult lostRace = failedWith(409,
                problem(409, "optimistic-lock", Problem.RETRY_SAME_REQUEST));
        assertTrue(lostRace.isTransientFailure());
        assertFalse(lostRace.isBufferable());
    }

    private static IngestResult failedWith(int status, Problem problem) {
        return new IngestResult(0, 10,
                List.of(new IngestResult.BatchError(10, status, "failed", null, problem)));
    }
}
