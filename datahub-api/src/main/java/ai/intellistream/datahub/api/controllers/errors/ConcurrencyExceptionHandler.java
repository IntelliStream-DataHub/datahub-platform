// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.controllers.errors;

import ai.intellistream.datahub.errors.ResponseError;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Translates concurrency-control failures surfaced by Spring Data / Hibernate into HTTP 409
 * Conflict. These happen when a {@code @Version}-annotated entity (e.g. NodeEntity) is
 * updated based on a stale read — either another writer committed first, or the row was
 * deleted before the UPDATE landed. Returning 409 here gives callers a clean, retryable
 * signal instead of the generic 500 they'd otherwise see from the global {@code catch
 * RuntimeException} blocks in each controller.
 *
 * <p>We catch the broader {@link OptimisticLockingFailureException} (superclass of the
 * more specific {@code ObjectOptimisticLockingFailureException}) so any future lock-flavor
 * Spring Data might throw is also covered.
 *
 * <p>The response shape is {@link ConflictError} so it shows up as a concrete schema in the
 * OpenAPI spec — clients can discriminate on {@code cause = "concurrency"} instead of
 * string-matching the message.
 */
@RestControllerAdvice
@Slf4j
public class ConcurrencyExceptionHandler {

    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<ResponseError<ConflictError>> handleOptimisticLock(OptimisticLockingFailureException ex) {
        // Log at info — this is expected under contention and not an operator alert.
        log.info("Optimistic lock conflict: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(
                ConflictError.of("The resource was modified or removed by another request. Re-read and retry.")
        );
    }
}
