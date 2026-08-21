// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.controllers.errors;

import ai.intellistream.datahub.errors.ResponseError;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

/**
 * 409 body for concurrency conflicts — a write was applied to a row that had already been
 * updated or deleted by another request. Distinct from {@link DuplicateError} (which models
 * "external id already exists" at create time) so clients can distinguish a retry-me
 * situation from a content fix-it situation purely from the response schema.
 *
 * <p>The standard response is {@code {"error": {"code": 409, "cause": "concurrency",
 * "message": "..."}}}.
 */
@Getter
@Setter
@Schema(name = "ConflictError",
        description = "Concurrency conflict — the targeted resource was modified or removed " +
                "by another request between read and write. Clients should re-fetch the " +
                "current state and retry.")
public class ConflictError {

    /** Always 409. Kept in the body for parity with BadRequestError / DuplicateError. */
    private int code = 409;

    /**
     * Discriminator so clients can branch on conflict kind without string-matching the
     * message. Currently the only value is {@code concurrency}; reserved for future flavours
     * (e.g. {@code pessimistic_lock_timeout}).
     */
    @Schema(description = "Machine-readable conflict cause.",
            allowableValues = {"concurrency"},
            example = "concurrency")
    private String cause = "concurrency";

    @Schema(description = "Human-readable explanation intended for logs and UIs.",
            example = "The resource was modified or removed by another request. Re-read and retry.")
    private String message;

    public static ResponseError<ConflictError> of(String message) {
        var err = new ConflictError();
        err.setMessage(message);
        return new ResponseError<ConflictError>().setError(err);
    }
}
