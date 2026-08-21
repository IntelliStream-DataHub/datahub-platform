// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.dhconsole.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.ClientAuthorizationException;
import org.springframework.security.oauth2.client.ClientAuthorizationRequiredException;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * An idle console tab keeps its own session alive with the /is-logged-in poll, so Keycloak's SSO
 * session is usually the first thing to die. When it does, the refresh grant comes back as
 * invalid_grant and the client manager throws a plain ClientAuthorizationException — which
 * OAuth2AuthorizationRequestRedirectFilter does not recognise, so it used to reach the browser as a
 * Whitelabel 500. Pin that it is normalised into the exception the filter does act on.
 */
class AccessTokensTest {

    private static final String REGISTRATION_ID = "keycloak";

    private MockHttpServletRequest request;
    private MockHttpServletResponse response;

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void rejectedRefreshAsksForReauthorizationAndDropsTheSession() {
        MockHttpSession session = authenticatedRequestWithSession();
        OAuth2AuthorizedClientManager manager = mock(OAuth2AuthorizedClientManager.class);
        when(manager.authorize(any())).thenThrow(new ClientAuthorizationException(
                new OAuth2Error("invalid_grant", "Token is not active", null), REGISTRATION_ID));

        assertThatThrownBy(() -> new AccessTokens(manager).token())
                .isInstanceOf(ClientAuthorizationRequiredException.class);
        assertThat(session.isInvalid()).isTrue();
        // The page the user was on is parked in the fresh session, so logging back in returns them
        // there rather than to the dashboard.
        assertThat(new HttpSessionRequestCache().getRequest(request, response))
                .isNotNull()
                .extracting(saved -> saved.getRedirectUrl())
                .asString().contains("/timeseries");
    }

    @Test
    void missingAuthorizedClientAlsoAsksForReauthorization() {
        MockHttpSession session = authenticatedRequestWithSession();
        OAuth2AuthorizedClientManager manager = mock(OAuth2AuthorizedClientManager.class);
        when(manager.authorize(any())).thenReturn(null);

        assertThatThrownBy(() -> new AccessTokens(manager).token())
                .isInstanceOf(ClientAuthorizationRequiredException.class);
        assertThat(session.isInvalid()).isTrue();
    }

    private MockHttpSession authenticatedRequestWithSession() {
        var principal = new DefaultOAuth2User(
                List.of(new SimpleGrantedAuthority("DATAHUB_CONSOLE")),
                Map.of("sub", "user-1"),
                "sub");
        SecurityContextHolder.getContext().setAuthentication(
                new OAuth2AuthenticationToken(principal, principal.getAuthorities(), REGISTRATION_ID));

        MockHttpSession session = new MockHttpSession();
        this.request = new MockHttpServletRequest("GET", "/timeseries");
        this.request.addHeader("Accept", "text/html,application/xhtml+xml");
        this.request.setSession(session);
        this.response = new MockHttpServletResponse();
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request, response));
        return session;
    }
}
