// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.dhconsole.security;

import ai.intellistream.datahub.tenant.TenantConfigService;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.PathNotFoundException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.security.autoconfigure.actuate.web.servlet.EndpointRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.authority.mapping.GrantedAuthoritiesMapper;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.client.oidc.web.logout.OidcClientInitiatedLogoutSuccessHandler;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.HttpSessionOAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUserAuthority;
import org.springframework.security.oauth2.core.user.OAuth2UserAuthority;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationFailureHandler;
import org.springframework.security.web.authentication.logout.LogoutSuccessHandler;
import org.springframework.security.web.authentication.logout.SimpleUrlLogoutSuccessHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.GenericFilterBean;
import org.springframework.web.util.UriComponents;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@Slf4j
public class WebSecurityConfig {

    /** The actuator chain on the management port: the scrape is open, everything else denied. */
    @Bean
    @Order(1)
    SecurityFilterChain actuatorFilterChain(HttpSecurity http) throws Exception {
        http
                .securityMatcher(EndpointRequest.toAnyEndpoint())
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(EndpointRequest.to("prometheus")).permitAll()
                        .anyRequest().denyAll())
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
        return http.build();
    }

    @Bean
    OAuth2AuthorizedClientRepository authorizedClientRepository() {
        return new HttpSessionOAuth2AuthorizedClientRepository();
    }

    @Bean
    SecurityFilterChain
    clientSecurityFilterChain(HttpSecurity http, InMemoryClientRegistrationRepository clientRegistrationRepository)
            throws Exception {
        http.oauth2Login(oauth -> oauth.failureHandler(noOrganizationAwareFailureHandler()));
        http.logout(logout -> {
            logout.logoutUrl("/datahub-logout")
                    .invalidateHttpSession(true)
                    .clearAuthentication(true)
                    .deleteCookies("JSESSIONID")
                    .logoutSuccessHandler(oidcLogoutSuccessHandler(clientRegistrationRepository));
        });
        http.exceptionHandling(ex -> ex
                .accessDeniedHandler(new CustomAccessDeniedHandler())
        );
        // @formatter:off
        http.authorizeHttpRequests(ex -> ex
                .requestMatchers(
                        "/is-logged-in",
                        "/login/**",
                        "/oauth2/**",
                        "/static/css/**",
                        "/error/**",
                        "/datahub-logout"
                    ).permitAll()
                .requestMatchers("/**").hasAuthority("DATAHUB_CONSOLE")
                .anyRequest().authenticated());
        // @formatter:on
        return http.build();
    }

    private static LogoutSuccessHandler oidcLogoutSuccessHandler(InMemoryClientRegistrationRepository clientRegistrationRepository) {
        final var handler = new OidcClientInitiatedLogoutSuccessHandler(clientRegistrationRepository);
        // Without a post_logout_redirect_uri the identity provider decides where the user lands
        // after RP-initiated logout, which meant its own "you are logged out" page on the
        // Keycloak host — the same URL whatever hostname the console was served from. "{baseUrl}"
        // is expanded per request (honouring X-Forwarded-* via server.forward-headers-strategy),
        // so every deployment sends the user back to its own root, which is unauthenticated and
        // therefore bounces straight into the login screen. The trailing slash is deliberate: it
        // matches a "https://<console-host>/*" entry in Keycloak's valid post-logout redirect
        // URIs, which the client must list or Keycloak rejects the logout.
        handler.setPostLogoutRedirectUri("{baseUrl}/");
        return handler;
    }

    private AuthenticationFailureHandler noOrganizationAwareFailureHandler() {
        // Spring's default sends the user to /login?error. Intercept the specific
        // tenant-resolution errors we raise in GrantedAuthoritiesMapperImpl so the
        // user sees an explanation instead of a generic login failure. Invalidate
        // the session first to wipe any partial UserSession state written before
        // validation threw.
        final var fallback = new SimpleUrlAuthenticationFailureHandler();
        return (request, response, exception) -> {
            if (exception instanceof OAuth2AuthenticationException oae) {
                final var code = oae.getError().getErrorCode();
                if ("no_organization".equals(code) || "unknown_organization".equals(code)) {
                    log.info("Denied login ({}): {}", code, oae.getError().getDescription());
                    final var session = request.getSession(false);
                    if (session != null) {
                        session.invalidate();
                    }
                    response.sendRedirect(request.getContextPath() + "/error/no-organization");
                    return;
                }
            }
            fallback.onAuthenticationFailure(request, response, exception);
        };
    }

    static class LoginPageFilter extends GenericFilterBean {
        @Override
        public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException {
            final var auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth != null
                    && auth.isAuthenticated()
                    && !(auth instanceof AnonymousAuthenticationToken)
                    && ((HttpServletRequest) request).getRequestURI().equals("/login")) {
                ((HttpServletResponse) response).sendRedirect("/");
            }
            chain.doFilter(request, response);
        }

    }

    static class AlmostOidcClientInitiatedLogoutSuccessHandler extends SimpleUrlLogoutSuccessHandler {
        public AlmostOidcClientInitiatedLogoutSuccessHandler(
                LogoutProperties.ProviderLogoutProperties properties,
                ClientRegistration clientRegistration,
                String postLogoutRedirectUri) {
            super();
            this.properties = properties;
            this.clientRegistration = clientRegistration;
            this.postLogoutRedirectUri = postLogoutRedirectUri;
        }

        private final LogoutProperties.ProviderLogoutProperties properties;
        private final ClientRegistration clientRegistration;
        private final String postLogoutRedirectUri;

        @Override
        protected String determineTargetUrl(HttpServletRequest request, HttpServletResponse response, Authentication authentication) {
            if (authentication instanceof OAuth2AuthenticationToken oauthentication && authentication.getPrincipal() instanceof OidcUser oidcUser) {
                final var endSessionUri = UriComponentsBuilder.fromUri(properties.getLogoutUri()).queryParam("client_id", clientRegistration.getClientId())
                        .queryParam("id_token_hint", oidcUser.getIdToken().getTokenValue())
                        .queryParam(properties.getPostLogoutUriParameterName(), postLogoutRedirectUri(request).toString()).toUriString();
                return endSessionUri.toString();
            }
            return super.determineTargetUrl(request, response, authentication);
        }

        private String postLogoutRedirectUri(HttpServletRequest request) {
            if (this.postLogoutRedirectUri == null) {
                return null;
            }
            // @formatter:off
            UriComponents uriComponents = UriComponentsBuilder.fromUriString(request.getRequestURL().toString())
                    .replacePath(request.getContextPath())
                    .replaceQuery(null)
                    .fragment(null)
                    .build();

            Map<String, String> uriVariables = new HashMap<>();
            String scheme = uriComponents.getScheme();
            uriVariables.put("baseScheme", (scheme != null) ? scheme : "");
            uriVariables.put("baseUrl", uriComponents.toUriString());

            String host = uriComponents.getHost();
            uriVariables.put("baseHost", (host != null) ? host : "");

            String path = uriComponents.getPath();
            uriVariables.put("basePath", (path != null) ? path : "");

            int port = uriComponents.getPort();
            uriVariables.put("basePort", (port == -1) ? "" : ":" + port);

            uriVariables.put("registrationId", clientRegistration.getRegistrationId());

            return UriComponentsBuilder.fromUriString(this.postLogoutRedirectUri)
                    .buildAndExpand(uriVariables)
                    .toUriString();
            // @formatter:on
        }
    }

    @RequiredArgsConstructor
    static class DelegatingOidcClientInitiatedLogoutSuccessHandler implements LogoutSuccessHandler {
        private final Map<String, LogoutSuccessHandler> delegates;

        public DelegatingOidcClientInitiatedLogoutSuccessHandler(
                InMemoryClientRegistrationRepository clientRegistrationRepository,
                LogoutProperties properties,
                String postLogoutRedirectUri) {
            delegates = StreamSupport.stream(clientRegistrationRepository.spliterator(), false)
                    .collect(Collectors.toMap(ClientRegistration::getRegistrationId, clientRegistration -> {
                        final var registrationProperties = properties.getRegistration().get(clientRegistration.getRegistrationId());
                        if (registrationProperties == null) {
                            final var handler = new OidcClientInitiatedLogoutSuccessHandler(clientRegistrationRepository);
                            handler.setPostLogoutRedirectUri(postLogoutRedirectUri);
                            return handler;
                        }
                        return new AlmostOidcClientInitiatedLogoutSuccessHandler(registrationProperties, clientRegistration, postLogoutRedirectUri);
                    }));
        }

        @Override
        public void onLogoutSuccess(HttpServletRequest request, HttpServletResponse response, Authentication authentication)
                throws IOException,
                ServletException {
            if (authentication instanceof OAuth2AuthenticationToken oauthentication) {
                delegates.get(oauthentication.getAuthorizedClientRegistrationId()).onLogoutSuccess(request, response, authentication);
            }
        }

    }

    @Component
    @RequiredArgsConstructor
    static class GrantedAuthoritiesMapperImpl implements GrantedAuthoritiesMapper {

        private final AuthoritiesMappingProperties properties;
        private final UserSession userSession;
        private final TenantConfigService tenantConfigService;

        @Override
        public Collection<? extends GrantedAuthority> mapAuthorities(Collection<? extends GrantedAuthority> authorities) {
            Set<GrantedAuthority> mappedAuthorities = new HashSet<>();

            authorities.forEach(authority -> {
                if (OidcUserAuthority.class.isInstance(authority)) {
                    final var oidcUserAuthority = (OidcUserAuthority) authority;
                    final var issuer = oidcUserAuthority.getIdToken().getClaimAsURL(JwtClaimNames.ISS);
                    userSession.setName(oidcUserAuthority.getUserInfo().getFullName());
                    userSession.setOrganizationFromAttributes(oidcUserAuthority.getAttributes());
                    // The token is valid, but without an organization — or with an
                    // organization id that isn't registered as a tenant in the platform
                    // — we can't resolve a tenant for any downstream API call. Fail the
                    // login here rather than let the user in and 403 on every request.
                    final var orgId = userSession.getOrganizationId();
                    if (orgId == null || orgId.isBlank()) {
                        throw new OAuth2AuthenticationException(
                                new OAuth2Error("no_organization",
                                        "Authenticated user is not assigned to any organization.",
                                        null));
                    }
                    if (tenantConfigService.getConfig(orgId) == null) {
                        log.warn("Denying login for {}: organization id {} is not a registered tenant",
                                userSession.getName(), orgId);
                        throw new OAuth2AuthenticationException(
                                new OAuth2Error("unknown_organization",
                                        "Organization " + orgId + " is not a registered tenant.",
                                        null));
                    }
                    mappedAuthorities.addAll(extractAuthorities(oidcUserAuthority.getIdToken().getClaims(), properties.get(issuer)));

                } else if (OAuth2UserAuthority.class.isInstance(authority)) {
                    try {
                        final var oauth2UserAuthority = (OAuth2UserAuthority) authority;
                        final var userAttributes = oauth2UserAuthority.getAttributes();
                        final var issuer = new URL(userAttributes.get(JwtClaimNames.ISS).toString());
                        mappedAuthorities.addAll(extractAuthorities(userAttributes, properties.get(issuer)));

                    } catch (MalformedURLException e) {
                        throw new RuntimeException(e);
                    }
                }
            });

            return mappedAuthorities;
        };

        // Package-private rather than private so WebSecurityConfigAuthoritiesMappingTest can pin
        // the claim shapes Keycloak sends; the json-path expressions come from Vault, and a
        // silent change in how they resolve would cost every user their roles.
        @SuppressWarnings({ "rawtypes", "unchecked" })
        static
        Collection<GrantedAuthority>
        extractAuthorities(Map<String, Object> claims, AuthoritiesMappingProperties.IssuerAuthoritiesMappingProperties properties) {
            return Stream.of(properties.claims).flatMap(claimProperties -> {
                Object claim;
                try {
                    claim = JsonPath.read(claims, claimProperties.jsonPath);
                } catch (PathNotFoundException e) {
                    claim = null;
                }
                if (claim == null) {
                    return Stream.empty();
                }
                if (claim instanceof String claimStr) {
                    return Stream.of(claimStr.split(","));
                }
                if (claim instanceof String[] claimArr) {
                    return Stream.of(claimArr);
                }
                if (Collection.class.isAssignableFrom(claim.getClass())) {
                    final var iter = ((Collection) claim).iterator();
                    if (!iter.hasNext()) {
                        return Stream.empty();
                    }
                    final var firstItem = iter.next();
                    if (firstItem instanceof String) {
                        return (Stream<String>) ((Collection) claim).stream();
                    }
                    if (Collection.class.isAssignableFrom(firstItem.getClass())) {
                        return (Stream<String>) ((Collection) claim).stream().flatMap(colItem -> ((Collection) colItem).stream()).map(String.class::cast);
                    }
                }
                return Stream.empty();
            }).map(SimpleGrantedAuthority::new).map(GrantedAuthority.class::cast).toList();
        }
    }
}
