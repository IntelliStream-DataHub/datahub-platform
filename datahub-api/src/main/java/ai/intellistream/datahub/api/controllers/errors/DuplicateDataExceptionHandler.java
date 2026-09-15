// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.controllers.errors;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;

/**
 * A taken external id, as a 409 naming what collided.
 *
 * <p>Fourteen controllers caught this themselves and read the status back out of the payload —
 * {@code HttpStatusCode.valueOf(dupError.getError().getCode())} — where {@code code} was a field on
 * the body defaulting to 409. The status belongs on the response, not inside it.
 */
@RestControllerAdvice
@Slf4j
public class DuplicateDataExceptionHandler {

    @ExceptionHandler(DuplicateDataException.class)
    public ProblemDetail handle(DuplicateDataException ex) {
        DuplicateError error = ex.getError() == null ? null : ex.getError().getError();
        String detail = error == null ? "Already exists." : error.getMessage();
        log.debug("Rejecting duplicate: {}", detail);
        return Problems.duplicate(detail, error == null ? List.of() : error.getDuplicated());
    }
}
