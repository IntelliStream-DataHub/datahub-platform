// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.dhconsole.controllers;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The escape from {@code /error/no-organization}.
 *
 * <p>What is being pinned is that this never resolves to somewhere requiring authentication.
 * Anything that does re-enters the login it is meant to escape, and Keycloak — still holding a
 * valid SSO session — signs the user back in and fails organization resolution again. That loop is
 * what this endpoint exists to break, and it is invisible in any test that only checks a redirect
 * happened.
 */
class ProviderSignOutControllerTest {

    private static ClientRegistration.Builder registration() {
        return ClientRegistration.withRegistrationId("keycloak")
                .clientId("datahub-client")
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
                .authorizationUri("https://kc.example/realms/datahub/protocol/openid-connect/auth")
                .tokenUri("https://kc.example/realms/datahub/protocol/openid-connect/token");
    }

    @Test
    void sendsTheUserToTheProvidersEndSessionEndpoint() {
        var repository = new InMemoryClientRegistrationRepository(registration()
                .providerConfigurationMetadata(Map.of("end_session_endpoint",
                        "https://kc.example/realms/datahub/protocol/openid-connect/logout"))
                .build());

        String target = new ProviderSignOutController(repository).signOut(new MockHttpServletRequest());

        assertThat(target).startsWith("redirect:https://kc.example/")
                .contains("/logout")
                // Needed for Keycloak to identify the session to end when no id token is available.
                .contains("client_id=datahub-client")
                // Would be validated against the client's post-logout URIs, which do not include
                // the console root — passing it turns a working logout into an error page.
                .doesNotContain("post_logout_redirect_uri");
    }

    @Test
    void clearsWhateverLocalSessionIsLeft() {
        var repository = new InMemoryClientRegistrationRepository(registration()
                .providerConfigurationMetadata(Map.of("end_session_endpoint", "https://kc.example/logout"))
                .build());
        var request = new MockHttpServletRequest();
        var session = new MockHttpSession();
        request.setSession(session);

        new ProviderSignOutController(repository).signOut(request);

        assertThat(session.isInvalid()).isTrue();
    }

    @Test
    void fallsBackToTheNormalLogoutWhenTheProviderAdvertisesNoEndSessionEndpoint() {
        var repository = new InMemoryClientRegistrationRepository(registration().build());

        assertThat(new ProviderSignOutController(repository).signOut(new MockHttpServletRequest()))
                .isEqualTo("redirect:/datahub-logout");
    }
}
