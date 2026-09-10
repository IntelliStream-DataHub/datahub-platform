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
import java.time.format.DateTimeParseException;
import java.util.ArrayDeque;
import java.util.Deque;
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
     *
     * <p>A bad timestamp is the exception: its message comes from {@code DateTimeHandler}, is
     * written for the caller, and names the two accepted forms and what to do about a seconds
     * value — so it is forwarded along with a pointer to the field. Flattening that to "could not
     * be read" would leave the caller with a line and column and no idea the unit was the problem.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ProblemDetail handleUnreadableBody(HttpMessageNotReadableException ex) {
        JacksonException jackson = ex.getCause() instanceof JacksonException cause ? cause : null;
        boolean syntaxError = jackson instanceof StreamReadException;
        DateTimeParseException badTimestamp = timestampFailure(jackson);

        String detail;
        if (badTimestamp != null) {
            detail = badTimestamp.getMessage();
        } else if (syntaxError && jackson.getOriginalMessage() != null) {
            detail = jackson.getOriginalMessage();
        } else {
            detail = "The request body could not be read.";
        }

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail);
        problem.setTitle("Bad Request");
        problem.setType(URI.create("https://intellistream.ai/errors/unreadable-request-body"));

        if (badTimestamp != null) {
            String pointer = pointerOf(jackson);
            if (pointer != null) {
                problem.setProperty("pointer", pointer);
            }
        }

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

    /**
     * The {@link DateTimeParseException} behind an unreadable body, or {@code null} if the failure
     * was something else.
     *
     * <p>Only this one exception type has its message forwarded. Jackson's other binding failures
     * quote Java types — "cannot deserialize value of type {@code java.lang.Long}" — and this api
     * does not hand callers its internals; {@code DateTimeParseException} from
     * {@code DateTimeHandler} is written for the caller and names the accepted forms.
     */
    private static DateTimeParseException timestampFailure(Throwable ex) {
        for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
            if (cause instanceof DateTimeParseException parse) {
                return parse;
            }
            if (cause.getCause() == cause) {
                break;
            }
        }
        return null;
    }

    /**
     * The RFC 6901 pointer for the field that failed, from the path Jackson attached on the way
     * out. Same shape as the unknown-field pointers above, so a caller parses one rule, not two.
     */
    private static String pointerOf(JacksonException jackson) {
        if (jackson == null || jackson.getPath().isEmpty()) {
            return null;
        }
        Deque<String> segments = new ArrayDeque<>();
        for (JacksonException.Reference reference : jackson.getPath()) {
            if (reference.getPropertyName() != null) {
                segments.addLast(escape(reference.getPropertyName()));
            } else if (reference.getIndex() >= 0) {
                segments.addLast(String.valueOf(reference.getIndex()));
            }
        }
        return segments.isEmpty() ? null : "#/" + String.join("/", segments);
    }

    /** RFC 6901 requires {@code ~} and {@code /} to be escaped inside a pointer segment. */
    private static String escape(String segment) {
        return segment.replace("~", "~0").replace("/", "~1");
    }
}
