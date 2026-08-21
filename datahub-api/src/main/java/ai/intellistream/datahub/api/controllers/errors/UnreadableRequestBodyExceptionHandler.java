// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.controllers.errors;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import tools.jackson.core.JacksonException;
import tools.jackson.core.TokenStreamLocation;
import tools.jackson.core.exc.StreamReadException;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Translates an unreadable request body into an RFC 9457 {@code application/problem+json}
 * <strong>400</strong>, and names the offending field when the body is merely unrecognised rather
 * than malformed.
 *
 * <p>This api rejects unknown properties ({@code fail-on-unknown-properties}), because silently
 * dropping a field the caller believed in is worse than refusing it: a typo'd or retired field
 * otherwise reads as a successful 200 that changed nothing. Refusing is only an improvement if the
 * caller can tell <em>which</em> field, so the response carries the property name and the ones the
 * endpoint does accept.
 *
 * <p>Without this, Spring's default surfaces Jackson's own message, which reads
 * {@code Unrecognized field "eventTime" (class ...EventFields), not marked as ignorable} — it
 * leaks the internal class name and package structure, and does not match the problem+json every
 * other error from this api uses.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
@Slf4j
public class UnreadableRequestBodyExceptionHandler {

    /**
     * Every unknown field in the body, as RFC 9457's {@code errors} extension: one entry per
     * offender, each located by a JSON Pointer and carrying the names its own position accepts.
     *
     * <p>Per-entry rather than one flat list, because two unknown fields at different depths accept
     * different names — a merged list would offer the caller names invalid where they put them, and
     * a bare name cannot tell {@code #/description/bogus} from a top-level {@code bogus}.
     */
    @ExceptionHandler(UnknownRequestFieldsException.class)
    public ProblemDetail handleUnknownFields(UnknownRequestFieldsException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
        problem.setTitle("Bad Request");
        problem.setType(URI.create("https://intellistream.ai/errors/unreadable-request-body"));
        problem.setProperty("errors", ex.getUnknownFields().stream().map(field -> {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("detail", "Unknown field");
            entry.put("pointer", field.pointer());
            if (!field.allowed().isEmpty()) {
                entry.put("allowedFields", field.allowed().stream().sorted().toList());
            }
            return entry;
        }).toList());

        log.debug("Unknown request fields: {}", ex.getMessage());
        return problem;
    }

    /**
     * A body that could not be parsed at all — malformed JSON, or a value of the wrong shape.
     * Unknown <em>fields</em> arrive as {@link UnknownRequestFieldsException} instead: parsing has
     * to finish before anything can be bound, so a syntax error is reported alone and the fields
     * further down the body are never examined.
     *
     * <p>Carries the line and column, because "could not be read" on a body of any size is a
     * needle-in-a-haystack instruction. For a pure syntax error Jackson's own wording is used
     * verbatim — it is precise and it names characters, not classes. Other parse failures keep the
     * generic wording, since their messages quote the Java types involved.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ProblemDetail handleUnreadableBody(HttpMessageNotReadableException ex) {
        JacksonException jackson = ex.getCause() instanceof JacksonException cause ? cause : null;
        boolean syntaxError = jackson instanceof StreamReadException;

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
                syntaxError && jackson.getOriginalMessage() != null
                        ? jackson.getOriginalMessage()
                        : "The request body could not be read.");
        problem.setTitle("Bad Request");
        problem.setType(URI.create("https://intellistream.ai/errors/unreadable-request-body"));

        TokenStreamLocation location = jackson == null ? null : jackson.getLocation();
        if (location != null && location.getLineNr() > 0) {
            problem.setProperty("line", location.getLineNr());
            problem.setProperty("column", location.getColumnNr());
        }

        // Debug, not warn: a malformed body is the caller's mistake and is fully described by the
        // response. Logging every one at warn hands any client a way to fill this service's logs.
        log.debug("Unreadable request body: {}", ex.getMessage());
        return problem;
    }
}
