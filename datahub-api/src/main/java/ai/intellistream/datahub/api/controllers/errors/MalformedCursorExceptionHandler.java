// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.controllers.errors;

import ai.intellistream.datahub.models.paging.MalformedCursorException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;

/**
 * Renders a rejected page cursor as a 400.
 *
 * <p>The exception travels from the service that raised it and is rendered here, rather than being
 * translated into a {@code BadRequestException} on the way. Translating cost a type and bought
 * nothing: the condition already had one that says what happened, and flattening it meant this
 * advice could not tell a stale cursor from any other bad request.
 *
 * <p>RFC 9457, like every other advice here. The {@code type} URI is the point — it gives a client
 * something stable to branch on, so "my cursor is stale, start the walk again" is a check on a URI
 * rather than a match on English prose. That is worth more for this error than for most: it is the
 * one a correct client can hit through no fault of its own, by holding a cursor across a deploy.
 */
@RestControllerAdvice
@Slf4j
public class MalformedCursorExceptionHandler {

    public static final String PROBLEM_TYPE = "https://intellistream.ai/errors/malformed-cursor";

    @ExceptionHandler(MalformedCursorException.class)
    public ProblemDetail handleMalformedCursor(MalformedCursorException ex) {
        log.debug("Rejecting page cursor: {}", ex.getMessage());

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
        problem.setTitle("Malformed cursor");
        problem.setType(URI.create(PROBLEM_TYPE));
        return problem;
    }
}
