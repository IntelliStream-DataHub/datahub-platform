// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.dhconsole.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.ClientAuthorizationException;
import org.springframework.security.oauth2.client.ClientAuthorizationRequiredException;
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;
import org.springframework.security.web.savedrequest.RequestCache;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@Component
@Slf4j
public class AccessTokens {

    private final OAuth2AuthorizedClientManager authorizedClientManager;
    private final RequestCache requestCache = new HttpSessionRequestCache();

    public AccessTokens(OAuth2AuthorizedClientManager authorizedClientManager) {
        this.authorizedClientManager = authorizedClientManager;
    }

    public String bearer() {
        return "Bearer " + token();
    }

    public String token() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (!(auth instanceof OAuth2AuthenticationToken oauth)) {
            throw new IllegalStateException("Not an OAuth2 session");
        }
        String registrationId = oauth.getAuthorizedClientRegistrationId();
        OAuth2AuthorizeRequest req = OAuth2AuthorizeRequest
                .withClientRegistrationId(registrationId)
                .principal(auth)
                .build();
        try {
            OAuth2AuthorizedClient client = authorizedClientManager.authorize(req);
            if (client == null || client.getAccessToken() == null) {
                throw new ClientAuthorizationRequiredException(registrationId);
            }
            return client.getAccessToken().getTokenValue();
        } catch (ClientAuthorizationException ex) {
            // Two different failures land here and mean the same thing — this session can no
            // longer produce an access token:
            //   * ClientAuthorizationRequiredException: no authorized client left in the session.
            //   * plain ClientAuthorizationException: the refresh grant was rejected (invalid_grant),
            //     which is what happens when Keycloak's SSO session died while our own session was
            //     kept alive by the /is-logged-in poll from an idle tab.
            // Only the first is understood by OAuth2AuthorizationRequestRedirectFilter, so the
            // second used to escape the filter chain as a Whitelabel 500. Normalise both to
            // ClientAuthorizationRequiredException so the filter restarts the authorization code
            // flow, and drop the session so /is-logged-in stops claiming the user is signed in.
            log.info("Re-authorization required for client {}: {}", registrationId, ex.getMessage());
            dropSessionAndRememberPage();
            if (ex instanceof ClientAuthorizationRequiredException required) {
                throw required;
            }
            throw new ClientAuthorizationRequiredException(registrationId);
        }
    }

    private void dropSessionAndRememberPage() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (!(attributes instanceof ServletRequestAttributes servletAttributes)) {
            // Called off a request thread — no session to drop.
            return;
        }
        HttpServletRequest request = servletAttributes.getRequest();
        HttpServletResponse response = servletAttributes.getResponse();
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        // Park the page the user was on in a fresh session so the login success handler returns
        // them to it instead of the dashboard. Only worth doing for a browser navigation — an
        // XHR is answered with a 401 by ApiSessionExpiredHandler and never redirected.
        if (response != null && isBrowserNavigation(request)) {
            requestCache.saveRequest(request, response);
        }
    }

    private static boolean isBrowserNavigation(HttpServletRequest request) {
        if (!HttpMethod.GET.matches(request.getMethod())) {
            return false;
        }
        String accept = request.getHeader(HttpHeaders.ACCEPT);
        return accept != null && accept.contains(MediaType.TEXT_HTML_VALUE);
    }
}
