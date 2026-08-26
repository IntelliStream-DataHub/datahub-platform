// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.config;

import ai.intellistream.datahub.api.filters.TenantProvisioningFilter;
import ai.intellistream.datahub.tenant.TenantConfigService;
import ai.intellistream.datahub.tenant.TenantContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.security.autoconfigure.actuate.web.servlet.EndpointRequest;
import org.springframework.boot.web.server.autoconfigure.ServerProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.springframework.security.config.Customizer.withDefaults;

@EnableWebSecurity
@EnableMethodSecurity
@Configuration
@Slf4j
public class SecurityConfig {

    @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}")
    private String issuerUri;

    /** The actuator chain on the management port: the scrape is open, everything else denied. */
    @Bean
    @Order(1)
    SecurityFilterChain actuatorFilterChain(HttpSecurity http) {
        http
                .securityMatcher(EndpointRequest.toAnyEndpoint())
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(EndpointRequest.to("prometheus")).permitAll()
                        .anyRequest().denyAll())
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
        return http.build();
    }

    @Bean
    SecurityFilterChain filterChain(
            HttpSecurity http,
            ServerProperties serverProperties,
            ObjectProvider<TenantConfigService> tenantConfigService,
            ObjectProvider<TenantFlywayMigrator> tenantMigrator,
            @Value("${origins:http://localhost:8080}") String[] origins,
            @Value("${permit-all:[]}") String[] permitAll
    ) {

        http
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/session/**").permitAll()
                        // The live-datapoint WebSocket handshake carries its JWT in the `token`
                        // query param (a browser WS can't send an Authorization header), so it is
                        // permitted here and validated inside DatapointListenWebSocketHandler.
                        .requestMatchers("/timeseries/datapoints/listen").permitAll()
                        .requestMatchers("/swagger-ui/**").permitAll()
                        .requestMatchers("/api-docs/**").permitAll()
                        .requestMatchers("/static/redoc/**").permitAll()
                        .requestMatchers(
                                Stream.of(permitAll)
                                        .map(pattern -> pattern.startsWith("/") ? pattern : "/" + pattern)
                                        .toArray(String[]::new))
                        .hasRole("DATAHUB_ACCESS")
                        // Every non-public endpoint requires ROLE_DATAHUB_ACCESS, not merely a
                        // valid JWT. This is the single enforcement point for the DATAHUB_ACCESS
                        // role across REST controllers AND the MCP tools (served as MVC endpoints
                        // on this same chain). A token with a valid organization claim but without
                        // this role can authenticate but cannot read or mutate any data.
                        .anyRequest().hasRole("DATAHUB_ACCESS")
                )
                .oauth2ResourceServer(oauth2 -> oauth2
                        .protectedResourceMetadata(metadata -> metadata
                                .protectedResourceMetadataCustomizer(builder -> builder
                                        .authorizationServer(issuerUri)
                                        .scope("openid")
                                )
                        )
                        .jwt(jwt -> jwt
                                .decoder(jwtDecoder())
                                .jwtAuthenticationConverter(jwtAuthenticationConverter())
                        )
                );

        // Refuse unknown tenants (403) and provision the request's tenant schema on first touch,
        // AFTER authz has run and OrganizationValidator has set TenantContext from the JWT. Gated
        // on TenantConfigService rather than on the migrator, so the unknown-tenant check still
        // applies where datahub.flyway.per-tenant-migrate is off; both absent (ObjectProvider
        // empty) in tests that stand up no tenant config.
        tenantConfigService.ifAvailable(configService ->
                http.addFilterAfter(new TenantProvisioningFilter(configService, tenantMigrator.getIfAvailable()),
                        AuthorizationFilter.class));

        // Enable and configure CORS
        http.cors(cors -> cors.configurationSource(corsConfigurationSource(origins)));

        // State-less session (state in access-token only)
        http.sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS));

        // Disable CSRF because of state-less session-management
        http.csrf(AbstractHttpConfigurer::disable);

        // Return 401 (unauthorized) instead of 302 (redirect to login) when
        // authorization is missing or invalid
        http.exceptionHandling(eh ->
                eh.authenticationEntryPoint((
                        request,
                        response,
                        authException
                ) -> {
            log.error("Authentication failure: {}", authException.getMessage());
            response.addHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer realm=\"Restricted Content\"");
            response.sendError(HttpStatus.UNAUTHORIZED.value(), HttpStatus.UNAUTHORIZED.getReasonPhrase());
        }));

        // If SSL enabled, disable http (https only)
        if (serverProperties.getSsl() != null && serverProperties.getSsl().isEnabled()) {
            http.redirectToHttps(withDefaults());
        }

        return http.build();
    }

    @Bean
    public JwtDecoder jwtDecoder() {
        NimbusJwtDecoder jwtDecoder = JwtDecoders.fromIssuerLocation(issuerUri);

        OAuth2TokenValidator<Jwt> withIssuer = JwtValidators.createDefaultWithIssuer(issuerUri);
        OAuth2TokenValidator<Jwt> organizationValidator = new OrganizationValidator();
        OAuth2TokenValidator<Jwt> validator = new DelegatingOAuth2TokenValidator<>(withIssuer, organizationValidator);

        jwtDecoder.setJwtValidator(validator);
        return jwtDecoder;
    }

    private JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(jwt -> {
            Map<String, Object> realmAccess = jwt.getClaimAsMap("realm_access");
            if (realmAccess != null && realmAccess.get("roles") instanceof List<?> roles) {
                return roles.stream()
                        .map(role -> new SimpleGrantedAuthority("ROLE_" + role))
                        .collect(Collectors.toList());
            }
            return Collections.emptyList();
        });
        return converter;
    }

    /**
     * Resolves the request's tenant from the token's {@code organization} claim and puts it in
     * {@link TenantContext}.
     *
     * <p>A token must name <strong>exactly one</strong> organization. Keycloak emits one entry per
     * organization the caller belongs to, and which one applies is the client's choice, made at the
     * token endpoint with {@code scope=organization:<alias>}. A token carrying several
     * ({@code scope=organization:*}) is ambiguous and is rejected rather than resolved by picking
     * one: this used to take {@code values().iterator().next()}, so a multi-organization user got
     * whichever organization happened to serialise first, and every request could have landed on a
     * different tenant's database.
     *
     * <p>See {@code datahub-api/KEYCLOAK_ORG_GROUPS.md} for the client and mapper configuration,
     * including the two ways this claim comes out empty or malformed.
     */
    static class OrganizationValidator implements OAuth2TokenValidator<Jwt> {

        /** Guards {@link #warnIfGroupsAreInTheToken}: one warning per JVM, not one per request. */
        private static final AtomicBoolean GROUPS_IN_TOKEN_WARNED = new AtomicBoolean();

        @Override
        public OAuth2TokenValidatorResult validate(Jwt jwt) {
            Object claim = jwt.getClaim("organization");

            if (claim == null) {
                return failure("Missing required organization context: the token carries no "
                        + "'organization' claim. The client must request the organization scope.");
            }

            // The stock membership mapper emits a flat array of aliases when addOrganizationId is
            // off. There is no id in that shape to resolve a tenant from, and it is a configuration
            // mistake rather than a malformed token, so say which knob to turn.
            if (claim instanceof Collection<?>) {
                return failure("Malformed organization claim: expected an object keyed by alias but "
                        + "got a list. Enable 'addOrganizationId' on the organization membership "
                        + "protocol mapper.");
            }

            if (!(claim instanceof Map<?, ?> orgs) || orgs.isEmpty()) {
                return failure("Missing required organization context: the 'organization' claim is empty.");
            }

            if (orgs.size() > 1) {
                return failure("Ambiguous organization context: the token names " + orgs.size()
                        + " organizations " + orgs.keySet()
                        + ". Request a single one with scope=organization:<alias>.");
            }

            Object only = orgs.values().iterator().next();
            if (only instanceof Map<?, ?> details && details.get("id") != null) {
                warnIfGroupsAreInTheToken(details);
                TenantContext.setTenantId(details.get("id").toString());
                return OAuth2TokenValidatorResult.success();
            }
            return failure("Malformed organization claim: no 'id' for organization "
                    + orgs.keySet() + ". Enable 'addOrganizationId' on the organization membership "
                    + "protocol mapper.");
        }

        private static OAuth2TokenValidatorResult failure(String description) {
            log.warn("Rejecting token: {}", description);
            return OAuth2TokenValidatorResult.failure(
                    new OAuth2Error("invalid_token", description, null));
        }

        /**
         * A configuration smell, not a bad token. The dataset grants are read from the UserInfo
         * endpoint whatever the token says, so a token carrying them is still valid and still
         * resolves the same tenant; it is not rejected.
         *
         * <p>What it does mean is that the realm's organization group-membership mapper has
         * drifted from {@code access.token.claim=false} (see
         * {@code deploy/keycloak/bootstrap-org-groups.sh}), which puts every grant back on every
         * request and re-couples revocation to token expiry. Nothing else notices, because nothing
         * else reads them, so this is the only place the drift is visible.
         *
         * <p>Logged once per JVM. This runs on every request, and a per-request warning about a
         * misconfiguration nobody can fix from here would be a log flood rather than a signal.
         */
        private static void warnIfGroupsAreInTheToken(Map<?, ?> organizationDetails) {
            if (organizationDetails.get("groups") == null
                    || !GROUPS_IN_TOKEN_WARNED.compareAndSet(false, true)) {
                return;
            }
            log.warn("The access token carries organization groups. DataHub ignores them and reads "
                    + "the dataset grants from UserInfo, so access is unaffected, but the "
                    + "organization group-membership mapper should have access.token.claim=false "
                    + "and id.token.claim=false. See datahub-api/KEYCLOAK_ORG_GROUPS.md.");
        }
    }

    private UrlBasedCorsConfigurationSource corsConfigurationSource(String[] origins) {
        final var configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(Arrays.asList(origins));
        configuration.setAllowedMethods(List.of("*"));
        configuration.setAllowedHeaders(List.of("*"));
        configuration.setExposedHeaders(List.of("*"));

        final var source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}