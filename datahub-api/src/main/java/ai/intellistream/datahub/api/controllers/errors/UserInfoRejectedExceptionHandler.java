package ai.intellistream.datahub.api.controllers.errors;

import ai.intellistream.datahub.api.datasecurity.UserInfoRejectedException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;

/**
 * Translates {@link UserInfoRejectedException} into an RFC 9457 {@code application/problem+json}
 * <strong>401</strong>.
 *
 * <p>401, not 503. The sibling {@code UserInfoUnavailableExceptionHandler} is for the identity
 * provider being unreachable, which is temporary and worth retrying. This one is for the identity
 * provider having answered, and having said no. Retrying that will fail identically forever; the
 * only thing that helps is a new token. Reporting it as 503 with a {@code Retry-After} sent people
 * hunting for an outage that was not happening.
 *
 * <p>And not 403, for the same reason the unavailable case is not 403: the caller is not forbidden
 * from the resource, they are no longer authenticated. A browser client can act on 401 — drop the
 * session and bounce to login — where 403 and 503 both leave it stuck.
 *
 * <p>Carries {@code WWW-Authenticate}, as RFC 9110 requires of every 401, and matches the shape
 * {@code SecurityConfig}'s authentication entry point already returns for a missing or invalid
 * token, so a client sees one consistent answer whether the token failed local validation or was
 * refused upstream.
 *
 * <p>Logged at warn, not error: a session ending is part of normal operation, and the previous
 * error-level line made routine expiry look like an incident.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
@Slf4j
public class UserInfoRejectedExceptionHandler {

    @ExceptionHandler(UserInfoRejectedException.class)
    public ResponseEntity<ProblemDetail> handleUserInfoRejected(UserInfoRejectedException ex) {
        log.warn("Identity provider refused the caller's token, returning 401: {}", ex.getMessage());

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.UNAUTHORIZED,
                "Your session is no longer valid. Sign in again.");
        problem.setTitle("Unauthorized");
        problem.setType(URI.create("https://datahub.intellistream.ai/errors/token-rejected"));

        // The upstream detail (which check failed, which host answered) stays in the log above.
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .header(HttpHeaders.WWW_AUTHENTICATE,
                        "Bearer error=\"invalid_token\", "
                                + "error_description=\"The identity provider rejected the access token\"")
                .body(problem);
    }
}
