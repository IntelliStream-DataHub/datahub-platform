// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.controllers.errors;

import ai.intellistream.datahub.api.controllers.errors.UnknownRequestFieldsException.UnknownField;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.http.MockHttpInputMessage;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.InstanceOfAssertFactories.list;
import static org.assertj.core.api.InstanceOfAssertFactories.map;

/**
 * How the two unreadable-body outcomes render.
 *
 * <p>Rejecting an unknown field only beats silently dropping it if the caller can tell which field
 * and what to send instead. The shape follows RFC 9457's {@code errors} extension — one entry per
 * offender with a JSON Pointer — and the wording states what is wrong and stops there.
 */
class UnreadableRequestBodyExceptionHandlerTest {

    private final UnreadableRequestBodyExceptionHandler handler = new UnreadableRequestBodyExceptionHandler();

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> errorsOf(ProblemDetail problem) {
        return (List<Map<String, Object>>) problem.getProperties().get("errors");
    }

    @Test
    void oneUnknownFieldIsNamedInTheSingular() {
        ProblemDetail problem = handler.handleUnknownFields(new UnknownRequestFieldsException(
                List.of(new UnknownField("#/evnetTime", Set.of("eventTime", "description")))));

        assertThat(problem.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
        assertThat(problem.getDetail()).isEqualTo("Unknown field: evnetTime");
        assertThat(errorsOf(problem)).singleElement().asInstanceOf(map(String.class, Object.class))
                .containsEntry("detail", "Unknown field")
                .containsEntry("pointer", "#/evnetTime");
    }

    /** All of them at once: a caller with three stale fields should not need three attempts. */
    @Test
    void severalUnknownFieldsEachGetTheirOwnEntry() {
        ProblemDetail problem = handler.handleUnknownFields(new UnknownRequestFieldsException(List.of(
                new UnknownField("#/alpha", Set.of("description")),
                new UnknownField("#/omega", Set.of("description")))));

        assertThat(problem.getDetail()).isEqualTo("Unknown fields: alpha, omega");
        assertThat(errorsOf(problem)).extracting(e -> e.get("pointer"))
                .containsExactly("#/alpha", "#/omega");
    }

    /**
     * Each entry carries the names its own position accepts. Two unknowns at different depths accept
     * different things, so one merged list would offer names invalid where the caller put them.
     */
    @Test
    void eachEntryCarriesItsOwnAllowedFields() {
        ProblemDetail problem = handler.handleUnknownFields(new UnknownRequestFieldsException(List.of(
                new UnknownField("#/alpha", Set.of("description", "metadata")),
                new UnknownField("#/description/bogus", Set.of("set", "setNull")))));

        assertThat(errorsOf(problem).get(0).get("allowedFields"))
                .asInstanceOf(list(String.class)).containsExactly("description", "metadata");
        assertThat(errorsOf(problem).get(1).get("allowedFields"))
                .asInstanceOf(list(String.class)).containsExactly("set", "setNull");
    }

    /** A pointer disambiguates a nested field from a top-level one of the same name. */
    @Test
    void thePointerLocatesANestedField() {
        ProblemDetail problem = handler.handleUnknownFields(new UnknownRequestFieldsException(
                List.of(new UnknownField("#/description/bogus", Set.of()))));

        assertThat(problem.getDetail()).isEqualTo("Unknown field: bogus");
        assertThat(errorsOf(problem)).singleElement().asInstanceOf(map(String.class, Object.class))
                .containsEntry("pointer", "#/description/bogus")
                .doesNotContainKey("allowedFields");
    }

    /** A 400 should say what to send, not describe the server's internals. */
    @Test
    void doesNotLeakTheInternalClassName() {
        ProblemDetail problem = handler.handleUnknownFields(new UnknownRequestFieldsException(
                List.of(new UnknownField("#/evnetTime", Set.of("eventTime")))));

        assertThat(problem.getDetail()).doesNotContain("EventFields", "ai.intellistream");
    }

    /**
     * A syntax error is reported with Jackson's own precise wording and, crucially, where it is —
     * "could not be read" alone leaves the caller hunting through their own payload.
     */
    @Test
    void aSyntaxErrorSaysWhatAndWhere() {
        ProblemDetail problem = handler.handleUnreadableBody(
                parseFailure("{\n  \"items\": [\n    { \"id\": 0 },\"apples\":1\n  ]\n}"));

        assertThat(problem.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
        assertThat(problem.getDetail()).contains("was expecting comma to separate Array entries");
        assertThat(problem.getProperties()).containsEntry("line", 3);
        assertThat(problem.getProperties()).containsKey("column");
    }

    /** Character-level wording is safe to pass through; type-level wording would name Java classes. */
    @Test
    void aSyntaxErrorDoesNotLeakInternals() {
        ProblemDetail problem = handler.handleUnreadableBody(
                parseFailure("{\"items\": [ }"));

        assertThat(problem.getDetail()).doesNotContain("ai.intellistream", "java.lang", "class ");
    }

    /** A non-Jackson cause has no location, and keeps the generic wording. */
    @Test
    void anUnlocatableFailureStillGetsAUsable400() {
        ProblemDetail problem = handler.handleUnreadableBody(new HttpMessageNotReadableException(
                "stream closed", new RuntimeException("boom"), new MockHttpInputMessage(new byte[0])));

        assertThat(problem.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
        assertThat(problem.getDetail()).isEqualTo("The request body could not be read.");
        assertThat(problem.getProperties()).isNullOrEmpty();
    }

    /** Built from a real parse so the exception shape stays honest. */
    private static HttpMessageNotReadableException parseFailure(String malformedJson) {
        try {
            JsonMapper.builder().build().readValue(malformedJson, java.util.Map.class);
            throw new AssertionError("expected a parse failure for: " + malformedJson);
        } catch (RuntimeException jackson) {
            return new HttpMessageNotReadableException(
                    jackson.getMessage(), jackson, new MockHttpInputMessage(new byte[0]));
        }
    }
}
