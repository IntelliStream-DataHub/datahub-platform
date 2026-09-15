// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.controllers.errors;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Renders an {@link InvalidTimestampException} as the same 422 the binding path answers with, so a
 * timestamp is refused identically wherever it was sent.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
@Slf4j
public class InvalidTimestampExceptionHandler {

    @ExceptionHandler(InvalidTimestampException.class)
    public ProblemDetail handleInvalidTimestamp(InvalidTimestampException ex) {
        // Debug, like the binding path: a mistyped timestamp is the caller's mistake and is fully
        // described by the response. Logging every one at warn hands a client a way to fill the log.
        log.debug("Rejecting timestamp: {}", ex.getMessage());

        // No pointer: these are parsed past binding, so there is no JSON path left to name. The
        // fields the exception carries are the locator instead.
        return Problems.invalidTimestamp(ex.getMessage(), null, ex.getFields());
    }
}
