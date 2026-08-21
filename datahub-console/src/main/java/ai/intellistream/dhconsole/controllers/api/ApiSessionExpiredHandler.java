// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.dhconsole.controllers.api;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.client.ClientAuthorizationException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * The page controllers let a re-authorization failure bubble out to
 * {@code OAuth2AuthorizationRequestRedirectFilter}, which bounces the browser through the
 * authorization code flow again. That is the wrong answer for the JSON endpoints under
 * {@code /api/**}: a 302 to Keycloak from a fetch() either fails CORS or resolves to a login page
 * the caller cannot parse. Answer those with a plain 401 instead — the signed-out dialog driven by
 * the /is-logged-in poll in application.js picks it up from there.
 */
@RestControllerAdvice(basePackageClasses = ApiSessionExpiredHandler.class)
@Slf4j
public class ApiSessionExpiredHandler {

    @ExceptionHandler(ClientAuthorizationException.class)
    public ResponseEntity<Map<String, Object>> handleSessionExpired(ClientAuthorizationException ex) {
        log.info("API call rejected, session can no longer be authorized: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(Map.of(
                        "status", 401,
                        "error", "Unauthorized",
                        "message", "Session expired. Sign in again."));
    }
}
