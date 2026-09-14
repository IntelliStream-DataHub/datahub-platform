// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.controllers.errors;

import org.springframework.http.ProblemDetail;
import ai.intellistream.datahub.errors.ResponseError;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Renders a {@link BadRequestException} that no controller caught as a 400.
 *
 * <h2>Why this did not exist</h2>
 * Every endpoint that threw one also caught it, so the exception never had to travel. The filter
 * endpoints broke that assumption: they reject a malformed cursor from inside a service, and none
 * of them has a {@code catch (BadRequestException)}. With no advice either, the exception simply
 * escaped — and an escaped exception does not become an error response. Three of the four returned
 * <em>200 with an empty body</em>, and {@code /datasets/filter}, whose catch-all swallowed it,
 * returned 500 "Internal programming error.". A caller sending a broken cursor was told the request
 * succeeded and matched nothing, which is the failure the rejection existed to prevent.
 *
 * <p>Catching locally in each handler would have fixed those four and left the next one to
 * rediscover it. An advice is the one place that cannot be forgotten by a new endpoint.
 *
 * <h2>Body shape</h2>
 * RFC 9457, like every other advice. This used to return the exception's own {@code ResponseError}
 * payload, and the reasoning was sound at the time: controllers catch this locally and return
 * exactly that, so converting the advice alone would have given one exception two shapes depending
 * on which endpoint raised it. That argument was against changing it <em>in isolation</em>, not
 * against the shape — so the local catches go in the same change, and the objection with them.
 *
 * <p>{@code message} becomes {@code detail} and {@code fields} becomes the {@code fields}
 * extension; {@code code} is dropped because it duplicated the HTTP status it was sent alongside.
 */
@RestControllerAdvice
@Slf4j
public class BadRequestExceptionHandler {

    @ExceptionHandler(BadRequestException.class)
    public ProblemDetail handle(BadRequestException ex) {
        BadRequestError error = ex.getError() == null ? null : ex.getError().getError();
        log.debug("Rejecting request: {}", error == null ? "no detail" : error.getMessage());
        return Problems.badRequest(
                error == null ? null : error.getMessage(),
                error == null ? null : error.getFields());
    }
}
