// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.controllers.errors;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * {@code @Valid} failures on a request body, as a 400 — the same shape as one raised in a service.
 *
 * <p>Which layer caught a rule is an implementation detail. A caller fixing their request should
 * not have to parse one shape when the check ran during binding and another when it ran in a
 * service, so this renders exactly what {@link ConstraintViolationExceptionHandler} does.
 *
 * <p>This advice is also what makes {@code @Valid} adoptable. Thirty-six handler parameters bind a
 * body with no {@code @Valid}, so their constraints never run and {@code DataWrapper}'s batch cap
 * is unenforced. Adding the annotation without this handler would have swapped those endpoints'
 * error bodies for Spring's default and left the API with a fourth shape, which is why that work
 * was held until this existed.
 */
@RestControllerAdvice
@Slf4j
public class RequestBodyValidationExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handle(MethodArgumentNotValidException ex) {
        log.debug("Rejecting request body: {} error(s)", ex.getAllErrors().size());
        return Problems.bindingFailure(ex.getAllErrors());
    }
}
