// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.flow.web;

import ai.intellistream.datahub.flow.definition.InvalidFlowException;
import ai.intellistream.datahub.flow.definition.ValidationProblem;
import ai.intellistream.datahub.models.paging.MalformedCursorException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The service's error responses: RFC 9457 problems with the api's problem types
 * ({@code https://intellistream.ai/errors/<slug>}), so that a client handles both services alike.
 *
 * <p>An invalid flow definition is a {@code validation-failed} problem whose {@code fields} members
 * carry a JSON Pointer into the definition and a message, the same member shape the api uses.
 */
@Slf4j
@RestControllerAdvice
public class FlowProblems {

    private static final String BASE = "https://intellistream.ai/errors/";

    /** A problem the request answers with, raised anywhere below a controller. */
    public static class ProblemException extends RuntimeException {

        private final transient ProblemDetail problem;
        private final Integer retryAfterSeconds;

        public ProblemException(ProblemDetail problem) {
            this(problem, null);
        }

        public ProblemException(ProblemDetail problem, Integer retryAfterSeconds) {
            super(problem.getDetail());
            this.problem = problem;
            this.retryAfterSeconds = retryAfterSeconds;
        }

        public ProblemDetail problem() {
            return problem;
        }
    }

    public static ProblemException notFound(String detail) {
        return new ProblemException(of(HttpStatus.NOT_FOUND, "not-found", detail));
    }

    public static ProblemException forbidden(String detail) {
        return new ProblemException(of(HttpStatus.FORBIDDEN, "forbidden", detail));
    }

    /** A 403 a dataset's access refused, rather than the flow grants. */
    public static ProblemException datasetForbidden(String detail) {
        return new ProblemException(of(HttpStatus.FORBIDDEN, "dataset-forbidden", detail));
    }

    public static ProblemException badRequest(String detail) {
        return new ProblemException(of(HttpStatus.BAD_REQUEST, "bad-request", detail));
    }

    public static ProblemException duplicate(String detail) {
        return new ProblemException(of(HttpStatus.CONFLICT, "duplicate", detail));
    }

    public static ProblemException conflict(String detail) {
        return new ProblemException(of(HttpStatus.CONFLICT, "conflict", detail));
    }

    /** A 401: datahub-api refused the caller's token when asked for their grants. */
    public static ProblemException tokenRejected(String detail) {
        return new ProblemException(of(HttpStatus.UNAUTHORIZED, "token-rejected", detail));
    }

    /** A 503: datahub-api could not be asked, so nothing can be decided about the caller. */
    public static ProblemException apiUnavailable(String detail) {
        return new ProblemException(of(HttpStatus.SERVICE_UNAVAILABLE, "permissions-unavailable", detail), 5);
    }

    /** A 400 listing every problem in a definition, each at its JSON Pointer. */
    public static ProblemException invalid(List<ValidationProblem> problems) {
        ProblemDetail problem = of(HttpStatus.BAD_REQUEST, "validation-failed",
                "The flow definition is invalid.");
        problem.setTitle("Validation failed");
        problem.setProperty("fields", problems.stream().map(FlowProblems::member).toList());
        return new ProblemException(problem);
    }

    @ExceptionHandler(ProblemException.class)
    ResponseEntity<ProblemDetail> handle(ProblemException e) {
        ResponseEntity.BodyBuilder response = ResponseEntity.status(e.problem().getStatus())
                .contentType(MediaType.APPLICATION_PROBLEM_JSON);
        if (e.retryAfterSeconds != null) {
            response.header(HttpHeaders.RETRY_AFTER, String.valueOf(e.retryAfterSeconds));
        }
        return response.body(e.problem());
    }

    @ExceptionHandler(InvalidFlowException.class)
    ResponseEntity<ProblemDetail> handle(InvalidFlowException e) {
        return handle(invalid(e.problems()));
    }

    @ExceptionHandler(MalformedCursorException.class)
    ResponseEntity<ProblemDetail> handle(MalformedCursorException e) {
        return handle(new ProblemException(of(HttpStatus.BAD_REQUEST, "malformed-cursor", e.getMessage())));
    }

    private static Map<String, Object> member(ValidationProblem p) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("field", p.pointer());
        out.put("message", p.message());
        return out;
    }

    private static ProblemDetail of(HttpStatus status, String slug, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(URI.create(BASE + slug));
        return problem;
    }
}
