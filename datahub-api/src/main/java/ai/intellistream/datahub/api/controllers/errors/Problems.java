// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.controllers.errors;

import ai.intellistream.datahub.validation.FieldValidationError;
import jakarta.validation.ConstraintViolation;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;

import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * How this API describes a failure: one shape, built in one place.
 *
 * <h2>Why</h2>
 * Errors were rendered four different ways. Ten advices returned RFC 9457 {@link ProblemDetail};
 * two returned a {@code ResponseError<T>} wrapper; controllers hand-rolled bare strings in 27
 * places; and {@code BuildErrorResponse} returned a {@code DataWrapper} — a <em>success-shaped</em>
 * envelope, so a validation failure came back as {@code {"items":[{"externalId":"must not be
 * blank"}]}} and a client could not tell it from a successful listing by shape alone. Which one a
 * caller got depended on which endpoint they hit and whether a {@code catch} happened to be there.
 *
 * <h2>The shape</h2>
 * RFC 9457 {@code application/problem+json}: {@code type}, {@code title}, {@code status},
 * {@code detail}, plus extension members. Extensions are the spec's own mechanism (§3.2) and
 * consumers are required to ignore ones they do not recognise — so a member added later cannot
 * break a conforming client, which is not true of adding a field to a bespoke wrapper that a strict
 * deserializer may reject.
 *
 * <h2>{@code type} is the contract</h2>
 * The one member a client should branch on. Prose changes; a URI does not. All of them live under
 * {@link #BASE} — note that {@code UserInfoRejectedExceptionHandler} currently mints
 * {@code datahub.intellistream.ai}, a second host for the same scheme, which is a bug this class
 * exists to stop repeating. Adding a type here is a wire-contract decision, so they are declared
 * as constants rather than written inline at each throw site.
 *
 * <h2>{@code fields} keeps what the old shape threw away</h2>
 * {@link FieldValidationError} carries an i18n key and its arguments — {@code
 * resource.source.max.length.error} with the offending length — and {@code BuildErrorResponse}
 * collapsed each one into {@code Map.of(path, message)}, dropping both. A caller could therefore
 * not localise a message or read the limit programmatically; they got English prose. The
 * {@code fields} extension carries all four parts, so the response is more useful than the one it
 * replaces rather than merely tidier.
 */
public final class Problems {

    /** Every problem type this API mints. One host, one scheme. */
    public static final String BASE = "https://intellistream.ai/errors/";

    public static final URI VALIDATION_FAILED = type("validation-failed");
    public static final URI DUPLICATE = type("duplicate");
    public static final URI CONFLICT = type("conflict");
    public static final URI CONSTRAINT_VIOLATION = type("constraint-violation");
    public static final URI OPTIMISTIC_LOCK = type("optimistic-lock");
    public static final URI BAD_REQUEST = type("bad-request");

    private Problems() {
    }

    /** A problem type URI from its slug. Kebab-case, matching the ones already in use. */
    public static URI type(String slug) {
        return URI.create(BASE + slug);
    }

    /**
     * The base of every problem this class builds.
     *
     * <p>{@code detail} is prose for a human reading a log. It is deliberately not the thing a
     * client branches on — that is {@code type}.
     */
    public static ProblemDetail of(HttpStatusCode status, URI type, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail == null ? "" : detail);
        problem.setType(type);
        problem.setTitle(title);
        return problem;
    }

    /**
     * One rejected field: which one, why, and enough for the caller to say it in their own words.
     *
     * @param field    the property path, e.g. {@code externalId} or {@code items[0].name}
     * @param message  the resolved, human-readable reason
     * @param code     the i18n key, so a caller can localise rather than parse prose
     * @param rejected the offending value or bound, where the source carried one
     */
    public record FieldProblem(String field, String message, String code, Object rejected) {

        Map<String, Object> asMember() {
            // LinkedHashMap so the JSON key order is stable across responses — an unstable order
            // makes response diffs in tests and logs noisy for no reason.
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("field", field);
            out.put("message", message);
            if (code != null) {
                out.put("code", code);
            }
            if (rejected != null) {
                out.put("rejected", rejected);
            }
            return out;
        }
    }

    /** Attaches the {@code fields} extension, or leaves it off entirely when there is nothing to say. */
    public static ProblemDetail withFields(ProblemDetail problem, Collection<FieldProblem> fields) {
        if (fields == null || fields.isEmpty()) {
            return problem;
        }
        problem.setProperty("fields", fields.stream().map(FieldProblem::asMember).toList());
        return problem;
    }

    /** A 400 for bean-validation failures raised inside a service. */
    public static ProblemDetail constraintViolation(Collection<? extends ConstraintViolation<?>> violations) {
        List<FieldProblem> fields = new ArrayList<>();
        for (ConstraintViolation<?> violation : violations) {
            fields.add(new FieldProblem(
                    violation.getPropertyPath() == null ? null : violation.getPropertyPath().toString(),
                    violation.getMessage(),
                    // The template is the key before interpolation, e.g. {jakarta.validation…Size.message}
                    // or a project key like resource.source.max.length.error.
                    violation.getMessageTemplate(),
                    violation.getInvalidValue()));
        }
        return withFields(of(HttpStatus.BAD_REQUEST, CONSTRAINT_VIOLATION,
                "Validation failed", "One or more fields are invalid."), fields);
    }

    /**
     * A 400 for {@code @Valid} failures on a request body.
     *
     * <p>The same shape as {@link #constraintViolation}: whether a rule ran at the binding layer or
     * inside a service is an implementation detail, and a caller correcting their request should not
     * have to care which produced it.
     */
    public static ProblemDetail bindingFailure(List<ObjectError> errors) {
        List<FieldProblem> fields = new ArrayList<>();
        for (ObjectError error : errors) {
            String path = error instanceof FieldError fieldError ? fieldError.getField() : error.getObjectName();
            Object rejected = error instanceof FieldError fieldError ? fieldError.getRejectedValue() : null;
            fields.add(new FieldProblem(path, error.getDefaultMessage(), error.getCode(), rejected));
        }
        return withFields(of(HttpStatus.BAD_REQUEST, VALIDATION_FAILED,
                "Validation failed", "One or more fields are invalid."), fields);
    }

    /**
     * A 400 from the hand-written update validators.
     *
     * <p>{@link FieldValidationError} carries several codes per error; the first is the specific
     * one and the rest are progressively more general fallbacks, which is Spring's convention. Only
     * the specific one is published — the fallbacks are a resolution mechanism, not information the
     * caller needs.
     */
    public static ProblemDetail fieldValidation(Collection<FieldValidationError> errors) {
        List<FieldProblem> fields = new ArrayList<>();
        for (FieldValidationError error : errors) {
            String[] codes = error.getCodes();
            Object[] arguments = error.getArguments();
            fields.add(new FieldProblem(
                    error.getObjectName(),
                    error.getDefaultMessage(),
                    codes == null || codes.length == 0 ? null : codes[0],
                    arguments == null || arguments.length == 0 ? null : firstOrList(arguments)));
        }
        return withFields(of(HttpStatus.BAD_REQUEST, VALIDATION_FAILED,
                "Validation failed", "One or more fields are invalid."), fields);
    }

    /** A single argument reads better unwrapped; several are worth keeping as a list. */
    private static Object firstOrList(Object[] arguments) {
        return arguments.length == 1 ? arguments[0] : Arrays.asList(arguments);
    }

    /**
     * A 400 carrying the loose {@code field -> message} pairs the old {@code BadRequestError} used.
     *
     * <p>Those entries are not uniform — some are {@code externalId -> "must not be blank"}, others
     * {@code "DataSet.Id" -> "5"} — so they become a field and a message and nothing is invented.
     * New throw sites should build {@link FieldProblem}s directly and get a code and a rejected
     * value with them; this is the bridge for the ones that already exist.
     */
    public static ProblemDetail badRequest(String detail, Collection<Map<String, String>> legacyFields) {
        List<FieldProblem> fields = new ArrayList<>();
        if (legacyFields != null) {
            for (Map<String, String> entry : legacyFields) {
                entry.forEach((field, message) -> fields.add(new FieldProblem(field, message, null, null)));
            }
        }
        return withFields(of(HttpStatus.BAD_REQUEST, BAD_REQUEST, "Bad Request", detail), fields);
    }

    /**
     * A 409 for something that already exists.
     *
     * @param duplicated the identifiers that collided, as {@code field -> value}
     */
    public static ProblemDetail duplicate(String detail, Collection<Map<String, String>> duplicated) {
        ProblemDetail problem = of(HttpStatus.CONFLICT, DUPLICATE, "Conflict", detail);
        if (duplicated != null && !duplicated.isEmpty()) {
            problem.setProperty("duplicated", duplicated);
        }
        return problem;
    }

    /**
     * A 409 for a write that lost a race.
     *
     * <p>{@code cause} used to be a bare string on {@code ConflictError} defaulting to
     * {@code "concurrency"}. It is a type URI now, so a caller can tell an optimistic-lock conflict
     * from a duplicate one without reading prose.
     */
    public static ProblemDetail conflict(URI type, String detail) {
        return of(HttpStatus.CONFLICT, type == null ? CONFLICT : type, "Conflict", detail);
    }
}
