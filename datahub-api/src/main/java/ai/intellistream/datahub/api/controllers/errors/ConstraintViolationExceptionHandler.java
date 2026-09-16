// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.controllers.errors;

import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Bean-validation failures raised inside a service, as a 400.
 *
 * <p>There was no advice for this, so all seventeen controllers caught it themselves — twenty-six
 * {@code catch (ConstraintViolationException)} blocks, each calling {@code BuildErrorResponse} and
 * getting back a {@code DataWrapper}: a <em>success-shaped</em> envelope used as an error body.
 * Two of them ({@code ResourceController.filter}, {@code DataSetController.filter}) returned
 * {@code e.getMessage()} as a bare string instead, so the same failure had two shapes depending on
 * which endpoint produced it.
 *
 * <p>With this in place those catches are redundant and can go, which is what makes the shape
 * uniform rather than merely defined.
 */
@RestControllerAdvice
@Slf4j
public class ConstraintViolationExceptionHandler {

    @ExceptionHandler(ConstraintViolationException.class)
    public ProblemDetail handle(ConstraintViolationException ex) {
        log.debug("Rejecting request: {} constraint violation(s)", ex.getConstraintViolations().size());
        return Problems.constraintViolation(ex.getConstraintViolations());
    }
}
