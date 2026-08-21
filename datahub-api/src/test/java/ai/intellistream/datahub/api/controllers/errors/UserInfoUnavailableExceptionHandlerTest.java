// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.controllers.errors;

import ai.intellistream.datahub.api.datasecurity.UserInfoUnavailableException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A failure to <em>determine</em> permissions must not look like a denial. If it surfaced as 403,
 * an identity-provider outage would show users an empty dataset list instead of an error.
 */
class UserInfoUnavailableExceptionHandlerTest {

    private final UserInfoUnavailableExceptionHandler handler = new UserInfoUnavailableExceptionHandler();

    @Test
    void mapsToServiceUnavailableRatherThanForbidden() {
        ProblemDetail problem = handler.handleUserInfoUnavailable(
                new UserInfoUnavailableException("connection refused"));

        assertThat(problem.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE.value());
        assertThat(problem.getStatus()).isNotEqualTo(HttpStatus.FORBIDDEN.value());
        assertThat(problem.getType().toString()).endsWith("/permissions-unavailable");
    }

    /** The caller is told this is temporary, so a client does not treat it as "no access". */
    @Test
    void saysItIsTemporaryAndNotADenial() {
        ProblemDetail problem = handler.handleUserInfoUnavailable(
                new UserInfoUnavailableException("UserInfo returned HTTP 502"));

        assertThat(problem.getDetail()).contains("temporary fault", "not a denial");
        assertThat(problem.getProperties()).containsKey("retryAfter");
    }

    /**
     * The upstream message may name internal hosts, so it stays in the log rather than the body.
     */
    @Test
    void doesNotLeakTheUpstreamMessageToTheCaller() {
        ProblemDetail problem = handler.handleUserInfoUnavailable(
                new UserInfoUnavailableException("UserInfo request failed: keycloak.internal:8443 refused"));

        assertThat(problem.getDetail()).doesNotContain("keycloak.internal");
    }
}
