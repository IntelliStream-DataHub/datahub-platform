package ai.intellistream.datahub.api.controllers.errors;

import ai.intellistream.datahub.api.datasecurity.UserInfoRejectedException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A refused token must not look like an outage. Reported as 503 it tells the caller to retry, which
 * can never succeed, and hides the one remedy that works.
 */
class UserInfoRejectedExceptionHandlerTest {

    private final UserInfoRejectedExceptionHandler handler = new UserInfoRejectedExceptionHandler();

    @Test
    void mapsToUnauthorisedRatherThanUnavailableOrForbidden() {
        ResponseEntity<ProblemDetail> response = handler.handleUserInfoRejected(
                new UserInfoRejectedException("UserInfo rejected the access token with HTTP 401", 401));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
        assertThat(response.getBody().getStatus())
                .isNotEqualTo(HttpStatus.SERVICE_UNAVAILABLE.value())
                .isNotEqualTo(HttpStatus.FORBIDDEN.value());
        assertThat(response.getBody().getType().toString()).endsWith("/token-rejected");
    }

    /** RFC 9110 requires it on a 401, and clients key off it to restart authentication. */
    @Test
    void carriesAWwwAuthenticateChallenge() {
        ResponseEntity<ProblemDetail> response = handler.handleUserInfoRejected(
                new UserInfoRejectedException("UserInfo rejected the access token with HTTP 401", 401));

        assertThat(response.getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE))
                .startsWith("Bearer")
                .contains("invalid_token");
    }

    /** The remedy is re-authentication, so say that rather than inviting a retry. */
    @Test
    void tellsTheCallerToSignInAgainAndDoesNotOfferARetry() {
        ResponseEntity<ProblemDetail> response = handler.handleUserInfoRejected(
                new UserInfoRejectedException("UserInfo rejected the access token with HTTP 403", 403));

        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getDetail()).contains("Sign in again");
        assertThat(response.getBody().getDetail()).doesNotContain("temporary", "retry");
        assertThat(response.getBody().getProperties() == null
                || !response.getBody().getProperties().containsKey("retryAfter")).isTrue();
    }

    /** Same rule as the unavailable handler: upstream detail belongs in the log, not the body. */
    @Test
    void doesNotLeakTheUpstreamMessageToTheCaller() {
        ResponseEntity<ProblemDetail> response = handler.handleUserInfoRejected(
                new UserInfoRejectedException("keycloak.internal:8443 said user_session_not_found", 401));

        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getDetail()).doesNotContain("keycloak.internal", "user_session_not_found");
    }
}
