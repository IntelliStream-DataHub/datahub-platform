// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.dhconsole.controllers;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Signing out when there is nothing signed in.
 *
 * <p>{@code /datahub-logout} performs RP-initiated logout, which Spring builds only from an
 * {@code OAuth2AuthenticationToken} carrying an {@code OidcUser} — it needs the id token to hint
 * with. That is exactly what a user sent to {@code /error/no-organization} does not have: their
 * login <em>failed</em>, so no security context was ever established and the session was
 * invalidated on the way here.
 *
 * <p>The result was a loop with no way out. {@code /datahub-logout} found nothing to log out,
 * fell through to its default success URL of {@code /}, which requires authentication, which
 * bounced to Keycloak, which still held a perfectly good SSO session and signed the user straight
 * back in — failing organization resolution again. The page told the user to sign out and then
 * would not let them.
 *
 * <p>So this goes to the provider's {@code end_session_endpoint} directly, which needs no id token.
 * Terminating the session at Keycloak is the only thing that breaks the loop: everything on this
 * side was already clear.
 */
@Slf4j
@Controller
public class ProviderSignOutController {

    private final InMemoryClientRegistrationRepository clientRegistrations;

    public ProviderSignOutController(InMemoryClientRegistrationRepository clientRegistrations) {
        this.clientRegistrations = clientRegistrations;
    }

    @GetMapping("/error/sign-out")
    public String signOut(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        SecurityContextHolder.clearContext();

        return endSessionUri()
                .map(uri -> "redirect:" + uri)
                // A provider configured without discovery has no end-session endpoint to send them
                // to. Nothing better to do than the normal path, which at least works when there
                // is a session to end.
                .orElse("redirect:/datahub-logout");
    }

    /**
     * The provider's end-session endpoint with {@code client_id}, from OIDC discovery.
     *
     * <p>No {@code post_logout_redirect_uri}: Keycloak validates it against the client's valid
     * post-logout URIs, which default to its redirect URIs — and the console root is not one of
     * them, so passing it turns a working logout into an error page. Without it Keycloak asks for
     * confirmation and shows its own signed-out page, which is a fine place to end up. A deployment
     * that wants to land back on the console can add the console root to the client's post-logout
     * URIs and pass it here.
     */
    private java.util.Optional<String> endSessionUri() {
        for (ClientRegistration registration : clientRegistrations) {
            Object endpoint = registration.getProviderDetails()
                    .getConfigurationMetadata().get("end_session_endpoint");
            if (endpoint != null) {
                return java.util.Optional.of(UriComponentsBuilder.fromUriString(endpoint.toString())
                        .queryParam("client_id", registration.getClientId())
                        .toUriString());
            }
        }
        log.warn("No end_session_endpoint discovered; cannot sign out at the identity provider");
        return java.util.Optional.empty();
    }
}
