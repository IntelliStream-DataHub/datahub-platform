// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.controllers.errors;

import ai.intellistream.datahub.errors.ObjectNotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Translates {@link ObjectNotFoundException}s that reach Spring MVC's exception resolver into an
 * RFC 9457 {@code application/problem+json} 404 response.
 *
 * <p>This is the shared 404 machinery for the {@code GET /xyz/{id}} endpoints. They throw
 * {@code ObjectNotFoundException} in two cases that are deliberately indistinguishable to the
 * caller: the id does not exist, or the caller may not read it. Folding "forbidden" into "not
 * found" (rather than letting the dataset-ACL check surface a 403) keeps a hidden resource's
 * existence from leaking through the status code. See {@code AccessDeniedExceptionHandler} for the
 * 403 path used by write/other endpoints.
 *
 * <p>Controllers that catch {@code ObjectNotFoundException} locally (e.g. to shape a bespoke body)
 * keep their own handling — a local {@code catch} wins over this advice.
 */
@RestControllerAdvice
@Slf4j
public class ObjectNotFoundExceptionHandler {

    @ExceptionHandler(ObjectNotFoundException.class)
    public ProblemDetail handleNotFound(ObjectNotFoundException ex) {
        log.debug("Object not found: {}", ex.getMessage());

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
        problem.setTitle("Not Found");
        return problem;
    }
}
