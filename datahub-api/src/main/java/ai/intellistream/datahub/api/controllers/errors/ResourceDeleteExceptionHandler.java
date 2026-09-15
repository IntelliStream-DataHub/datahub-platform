// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.controllers.errors;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * A refused delete, as a 400 listing what is still in the way.
 *
 * <p>Seven controllers caught this and returned the body the exception carried, which left it the
 * one failure in the API still answering in the {@code ResponseError} envelope after everything
 * else moved to RFC 9457 — a client had to special-case delete.
 *
 * <p>An eighth did not catch it: {@code POST /policies/delete} reaches the same guards through
 * {@code PolicyService.deletePolicies}, so a policy delete the graph refused came back as a bare
 * 500. Handling it in one place is what fixes that, rather than adding a matching {@code catch}.
 */
@RestControllerAdvice
@Slf4j
public class ResourceDeleteExceptionHandler {

    @ExceptionHandler(ResourceDeleteException.class)
    public ProblemDetail handle(ResourceDeleteException ex) {
        log.debug("Refusing delete: {}", ex.getMessage());
        return Problems.deleteBlocked(ex.getType(), ex.getMessage(), ex.getBlockedBy());
    }
}
