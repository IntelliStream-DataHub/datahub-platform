// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.controllers.errors;

import java.util.List;

/**
 * A request the API refuses before doing any work, with the fields that made it refuse.
 *
 * <p>It used to carry a {@code ResponseError<BadRequestError>} — a rendered response body, built at
 * the throw site, that {@link BadRequestExceptionHandler} then took apart again. Carrying the facts
 * instead means a throw site says what is wrong, and exactly one place decides how that looks on
 * the wire. {@code BadRequestError.code} went with it: it was always 400, sent alongside the 400
 * status it duplicated.
 */
public class BadRequestException extends RuntimeException {

    // transient: the fields are rebuilt per request at the throw site and must never be
    // Java-serialized with the throwable.
    private final transient List<Problems.FieldProblem> fields;

    public BadRequestException(String detail) {
        this(detail, (FieldErrors) null);
    }

    public BadRequestException(String detail, FieldErrors fields) {
        super(detail);
        this.fields = fields == null ? List.of() : fields.asList();
    }

    /** The one-field case, common enough to be worth not building a {@link FieldErrors} for. */
    public BadRequestException(String detail, String field, String message) {
        this(detail, new FieldErrors().addFieldError(field, message));
    }

    /** Never null; empty when the failure has no per-field breakdown. */
    public List<Problems.FieldProblem> getFields() {
        return fields;
    }
}
