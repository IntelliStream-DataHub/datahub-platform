// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.api.errors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reading the error contract from the wire.
 *
 * <p>The bodies here are the shape {@code ProblemWireShapeTest} and {@code ProblemSchemaParityTest}
 * assert the API emits — extension members at the top level, beside {@code type}, not nested under
 * a {@code properties} object.
 */
class ProblemTest {

    @Test
    @DisplayName("the standard members and the fields extension are read")
    void readsAValidationProblem() {
        Problem problem = Problem.of(400, """
                {"type":"https://intellistream.ai/errors/validation-failed",\
                "title":"Validation failed","status":400,\
                "detail":"One or more fields are invalid.","instance":"/resources/create",\
                "fields":[{"field":"source","message":"Source max length is 128 characters.",\
                "code":"resource.source.max.length.error","rejected":129}],\
                "requestId":"0199f2a4-6c1e-7b3a-9d4f-2e8c5a1b7d90","retry":"change-request"}""");

        assertEquals("https://intellistream.ai/errors/validation-failed", problem.type());
        assertEquals("validation-failed", problem.slug());
        assertEquals("Validation failed", problem.title());
        assertEquals(400, problem.status());
        assertEquals("/resources/create", problem.instance());
        assertEquals(Problem.RETRY_CHANGE_REQUEST, problem.retry());
        assertFalse(problem.retryable());
        assertEquals("0199f2a4-6c1e-7b3a-9d4f-2e8c5a1b7d90", problem.requestId());

        assertEquals(List.of(new Problem.FieldProblem("source", "Source max length is 128 characters.",
                "resource.source.max.length.error", 129)), problem.fields());
    }

    @Test
    @DisplayName("an extension this class does not know is kept, not refused")
    void keepsUnknownExtensionMembers() {
        // RFC 9457 §3.2: a conforming consumer ignores members it does not recognise. If a new one
        // could make deserialization throw, the API could not add one without breaking every client
        // compiled against an older version of this class.
        Problem problem = Problem.of(409, """
                {"type":"https://intellistream.ai/errors/duplicate","title":"Conflict","status":409,\
                "duplicated":[{"externalId":"pump-1"}],"somethingAddedLater":{"a":1}}""");

        assertEquals("duplicate", problem.slug());
        assertEquals(List.of(java.util.Map.of("externalId", "pump-1")), problem.extensions().get("duplicated"));
        assertEquals(java.util.Map.of("a", 1), problem.extensions().get("somethingAddedLater"));
    }

    @Test
    @DisplayName("a body that is not a problem document still yields one, carrying the status")
    void neverNullAndNeverThrowing() {
        // A load balancer's HTML, an empty 502, a token endpoint's OAuth2 error, a JSON array.
        for (String body : new String[] {null, "", "   ", "<html><body>502 Bad Gateway</body></html>",
                "[]", "\"just a string\"", "{\"error\":\"invalid_grant\"}"}) {
            Problem problem = Problem.of(502, body);
            assertNotNull(problem, "body: " + body);
            assertNull(problem.slug(), "body: " + body);
            assertNull(problem.retry(), "body: " + body);
            assertEquals(502, problem.status(), "body: " + body);
            assertTrue(problem.fields().isEmpty(), "body: " + body);
        }
    }

    @Test
    @DisplayName("the status in the body wins; the HTTP status fills in when there is none")
    void statusFallsBackToTheResponseStatus() {
        assertEquals(429, Problem.of(429, """
                {"type":"https://intellistream.ai/errors/rate-limit-exceeded","retry":"same-request"}""")
                .status());
        assertEquals(403, Problem.of(200, "{\"status\":403}").status());
    }

    @Test
    @DisplayName("slug is null for a type minted somewhere other than this API")
    void slugOnlyForOurOwnTypes() {
        assertNull(Problem.of(400, "{\"type\":\"about:blank\"}").slug());
        assertNull(Problem.of(400, "{\"type\":\"https://example.org/errors/duplicate\"}").slug());
        assertFalse(Problem.of(400, "{\"type\":\"about:blank\"}").is("duplicate"));
    }

    @Test
    @DisplayName("retryable is what the API said, not what the status suggests")
    void retryableFollowsTheRetryMember() {
        // A 409 the server says to repeat, and a 500 it says not to: neither is what the status alone
        // would have answered.
        assertTrue(Problem.of(409, """
                {"type":"https://intellistream.ai/errors/optimistic-lock","retry":"same-request"}""")
                .retryable());
        assertFalse(Problem.of(500, """
                {"type":"https://intellistream.ai/errors/internal","retry":"needs-operator"}""")
                .retryable());
    }
}
