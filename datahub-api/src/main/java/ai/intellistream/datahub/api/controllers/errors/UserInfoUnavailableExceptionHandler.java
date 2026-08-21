// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.controllers.errors;

import ai.intellistream.datahub.api.datasecurity.UserInfoUnavailableException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;

/**
 * Translates {@link UserInfoUnavailableException} into an RFC 9457 {@code application/problem+json}
 * <strong>503</strong>.
 *
 * <p>503, not 403. "We could not determine your permissions" and "you do not have permission" are
 * different outcomes: collapsing the first into the second would show a user an empty dataset list
 * during an identity-provider outage, which reads as data loss rather than as a temporary fault.
 * And not 500 either, since nothing is wrong with the request or with this service.
 *
 * <p>Only reached once the resolver has already exhausted its stale-serving window, so by the time
 * a caller sees this the identity provider has been unreachable for minutes rather than seconds.
 * Logged at error for that reason. {@code Retry-After} is advisory: the condition clears as soon as
 * UserInfo answers again.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
@Slf4j
public class UserInfoUnavailableExceptionHandler {

    private static final String RETRY_AFTER_SECONDS = "10";

    @ExceptionHandler(UserInfoUnavailableException.class)
    public ProblemDetail handleUserInfoUnavailable(UserInfoUnavailableException ex) {
        log.error("Cannot resolve caller permissions, failing closed: {}", ex.getMessage());

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.SERVICE_UNAVAILABLE,
                "Could not verify your permissions because the identity provider is unreachable. "
                        + "This is a temporary fault, not a denial; retry shortly.");
        problem.setTitle("Service Unavailable");
        problem.setType(URI.create("https://intellistream.ai/errors/permissions-unavailable"));
        problem.setProperty("retryAfter", RETRY_AFTER_SECONDS);
        return problem;
    }
}
