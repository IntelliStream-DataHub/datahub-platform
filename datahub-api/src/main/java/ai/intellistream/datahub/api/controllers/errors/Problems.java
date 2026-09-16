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
 * {@link #BASE}. Adding a type here is a wire-contract decision, so they are declared as constants
 * rather than written inline at each throw site.
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

    /** A 409: a timeseries cannot be deleted while a subscription still reads it. */
    public static final URI REFERENCED = type("referenced");
    /** A 409: a deletion that would cut the surviving nodes off from the graph root. */
    public static final URI WOULD_STRAND = type("would-strand");

    public static final URI UNAUTHORIZED = type("unauthorized");
    public static final URI FORBIDDEN = type("forbidden");
    public static final URI NOT_FOUND = type("not-found");
    public static final URI METHOD_NOT_ALLOWED = type("method-not-allowed");
    public static final URI NOT_ACCEPTABLE = type("not-acceptable");
    public static final URI UNSUPPORTED_MEDIA_TYPE = type("unsupported-media-type");
    public static final URI INTERNAL = type("internal");
    public static final URI UNKNOWN_TENANT = type("unknown-tenant");
    public static final URI TENANT_PROVISIONING = type("tenant-provisioning");
    public static final URI FEATURE_DISABLED = type("feature-disabled");

    /** {@code retry}: the same request can succeed later; honour Retry-After when it is sent. */
    public static final String RETRY_SAME_REQUEST = "same-request";
    /** {@code retry}: only a different request can succeed. */
    public static final String RETRY_CHANGE_REQUEST = "change-request";
    /** {@code retry}: nothing the caller sends will succeed until an operator acts; quote the requestId. */
    public static final String RETRY_NEEDS_OPERATOR = "needs-operator";

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
     * @param rejected an i18n argument such as a length; never the submitted value itself
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
                    // Not the invalid value: it can be a credential or a whole object, and the caller has it.
                    null));
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
            // Not the rejected value: it can be a credential or a whole object, and the caller has it.
            fields.add(new FieldProblem(path, error.getDefaultMessage(), error.getCode(), null));
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

    /** A 404 for something the caller asked for by name and that is not there (or not theirs). */
    public static ProblemDetail notFound(String detail) {
        return of(HttpStatus.NOT_FOUND, NOT_FOUND, "Not Found", detail);
    }

    /** A 401; the detail is written here or by a token validator, never taken from a decoder's exception. */
    public static ProblemDetail unauthorized(String detail) {
        return of(HttpStatus.UNAUTHORIZED, UNAUTHORIZED, "Unauthorized", detail);
    }

    public static ProblemDetail forbidden(String detail) {
        return of(HttpStatus.FORBIDDEN, FORBIDDEN, "Forbidden", detail);
    }

    /** A 403 for an organization this deployment has no tenant for; only an operator can fix it. */
    public static ProblemDetail unknownTenant(String organizationId) {
        ProblemDetail problem = of(HttpStatus.FORBIDDEN, UNKNOWN_TENANT, "Forbidden",
                "Unknown organization: this deployment has no tenant for the organization in your "
                        + "token. Retrying will not help, the organization has to be onboarded.");
        problem.setProperty("organizationId", organizationId);
        return problem;
    }

    /** A 403 for a feature switched off for this organization; an operator turns it on. */
    public static ProblemDetail featureDisabled(String feature, String detail) {
        ProblemDetail problem = of(HttpStatus.FORBIDDEN, FEATURE_DISABLED, "Forbidden", detail);
        problem.setProperty("feature", feature);
        return problem;
    }

    /** A 503 while the tenant's schema is still being migrated; the caller should honour Retry-After. */
    public static ProblemDetail tenantProvisioning() {
        return of(HttpStatus.SERVICE_UNAVAILABLE, TENANT_PROVISIONING, "Service Unavailable",
                "This organization's database is still being prepared. Retry the same request shortly.");
    }

    /** The problem for a bare status, e.g. a 405 from Spring MVC or a sendError from a filter. */
    public static ProblemDetail forStatus(int status, String detail) {
        return switch (status) {
            case 400 -> badRequest(orElse(detail, "The request could not be processed as sent."));
            case 401 -> unauthorized(orElse(detail, "Authentication is required."));
            case 403 -> forbidden(orElse(detail, "The request is not allowed."));
            case 404 -> notFound(orElse(detail, "Nothing exists at this path."));
            case 405 -> of(HttpStatus.METHOD_NOT_ALLOWED, METHOD_NOT_ALLOWED, "Method Not Allowed",
                    orElse(detail, "This path does not accept this HTTP method."));
            case 406 -> of(HttpStatus.NOT_ACCEPTABLE, NOT_ACCEPTABLE, "Not Acceptable",
                    orElse(detail, "This endpoint cannot answer in a media type the Accept header allows."));
            case 415 -> of(HttpStatus.UNSUPPORTED_MEDIA_TYPE, UNSUPPORTED_MEDIA_TYPE, "Unsupported Media Type",
                    orElse(detail, "This endpoint does not accept this Content-Type."));
            case 500 -> internal(INTERNAL_DETAIL);
            default -> {
                HttpStatus known = HttpStatus.resolve(status);
                ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                        HttpStatusCode.valueOf(status), detail == null ? "" : detail);
                problem.setTitle(known == null ? "Error" : known.getReasonPhrase());
                yield problem;
            }
        };
    }

    /** What a caller is told about a failure inside the server. The cause stays in the log. */
    public static final String INTERNAL_DETAIL =
            "The server failed to complete the request. Nothing in the request was at fault.";

    private static String orElse(String detail, String fallback) {
        return detail == null || detail.isBlank() ? fallback : detail;
    }

    /** A 400 with no per-field breakdown — a malformed header, a path that will not parse. */
    public static ProblemDetail badRequest(String detail) {
        return of(HttpStatus.BAD_REQUEST, BAD_REQUEST, "Bad Request", detail);
    }

    /**
     * A 500 the caller can do nothing about.
     *
     * <p>The detail is deliberately incurious: the cause is logged server-side, and an internal
     * failure is not something to describe to a caller who cannot act on it.
     */
    public static ProblemDetail internal(String detail) {
        return of(HttpStatus.INTERNAL_SERVER_ERROR, INTERNAL, "Internal Server Error", detail);
    }

    /**
     * A 400 naming the fields that made the API refuse the request.
     *
     * <p>Same {@code fields} shape as {@link #constraintViolation} and {@link #bindingFailure}: a
     * caller correcting their request should not have to care whether the rule that rejected it
     * ran in a bean validator or in a hand-written check.
     */
    public static ProblemDetail badRequest(String detail, Collection<FieldProblem> fields) {
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

    /**
     * A 409 for a delete something still depends on, listing what is in the way.
     *
     * <p>409, not the 400 these used to answer: nothing is wrong with the request. It is
     * well-formed, the ids exist, and the caller may repeat it verbatim once the subscription is
     * removed or the stranded nodes are included. That is the definition of a conflict with the
     * current state of the resource, and it puts a refused delete alongside the other 409s — a
     * taken external id, a lost optimistic lock — which are the same kind of "try again once the
     * world changes" answer. A 400 told clients to fix their payload, which was never the remedy.
     *
     * <p>The blockers are structured records — a subscription's id and external id, or a stranded
     * node's external id — not the {@code field -> message} pairs {@link #badRequest(String,
     * Collection)} bridges. Flattening them through that bridge would turn one subscription into
     * four unrelated "field errors", so they keep their own extension member: a caller can read the
     * subscription external id and go delete it, which is the whole point of the message.
     */
    public static ProblemDetail deleteBlocked(URI type, String detail,
                                              Collection<Map<String, String>> blockedBy) {
        ProblemDetail problem = of(HttpStatus.CONFLICT, type == null ? CONFLICT : type,
                "Delete refused", detail);
        if (blockedBy != null && !blockedBy.isEmpty()) {
            problem.setProperty("blockedBy", blockedBy);
        }
        return problem;
    }

    /** Adds what every problem carries: the request's id and what the caller can do about it. */
    public static ProblemDetail decorate(ProblemDetail problem, String requestId) {
        Map<String, Object> properties = problem.getProperties();
        if (requestId != null && (properties == null || !properties.containsKey("requestId"))) {
            problem.setProperty("requestId", requestId);
        }
        if (properties == null || !properties.containsKey("retry")) {
            problem.setProperty("retry", retryFor(problem));
        }
        return problem;
    }

    static String retryFor(ProblemDetail problem) {
        String type = problem.getType() == null ? "" : problem.getType().toString();
        String slug = type.startsWith(BASE) ? type.substring(BASE.length()) : "";
        return switch (slug) {
            case "optimistic-lock", "rate-limit-exceeded", "ingest-quota-exceeded", "messaging-unavailable",
                 "permissions-unavailable", "tenant-provisioning" -> RETRY_SAME_REQUEST;
            case "unknown-tenant", "tenant-limit-reached", "feature-disabled", "dataset-forbidden", "internal" ->
                    RETRY_NEEDS_OPERATOR;
            default -> {
                int status = problem.getStatus();
                if (status == 429 || status == 502 || status == 503 || status == 504) {
                    yield RETRY_SAME_REQUEST;
                }
                yield status == 403 || status >= 500 ? RETRY_NEEDS_OPERATOR : RETRY_CHANGE_REQUEST;
            }
        };
    }

}
