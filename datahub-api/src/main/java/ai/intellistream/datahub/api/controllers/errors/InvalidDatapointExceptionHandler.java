// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.controllers.errors;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Renders an {@link InvalidDatapointException} as a <strong>422</strong>, the one status
 * {@code POST /timeseries/data} reserves for a payload the caller must change.
 *
 * <p>422 rather than 400 because the body is well formed and bound cleanly — the values are wrong
 * against the target series' declared type, which is only knowable once the series has been read.
 * And rather than the 500 it became when the catch-alls went: these used to be bare
 * {@link RuntimeException}s with no advice to catch them, so a mistyped value told the caller the
 * server had failed. {@code retry} settles the rest — {@code change-request}, which is what a 4xx
 * outside the retryable set resolves to.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
@Slf4j
public class InvalidDatapointExceptionHandler {

    @ExceptionHandler(InvalidDatapointException.class)
    public ProblemDetail handleInvalidDatapoint(InvalidDatapointException ex) {
        // Warn, not error: the payload is wrong, this service is not.
        log.warn("Datapoint insert rejected: {}", ex.getMessage());

        // The detail is the caller's own data coming back to them, so there is nothing to withhold.
        return Problems.of(HttpStatus.UNPROCESSABLE_CONTENT, Problems.INVALID_DATAPOINT,
                "Unprocessable Content", ex.getMessage());
    }
}
