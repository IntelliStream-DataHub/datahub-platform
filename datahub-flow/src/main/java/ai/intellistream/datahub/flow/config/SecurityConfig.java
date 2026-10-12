// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.flow.config;

import ai.intellistream.datahub.flow.tenant.FlowSchemaCheck;
import ai.intellistream.datahub.flow.tenant.FlowTenantFilter;
import ai.intellistream.datahub.tenant.OrganizationValidator;
import ai.intellistream.datahub.tenant.TenantConfigService;
import ai.intellistream.datahub.tenant.TenantContext;
import jakarta.servlet.Filter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.security.autoconfigure.actuate.web.servlet.EndpointRequest;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtDecoders;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import tools.jackson.databind.json.JsonMapper;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The trust boundary: an OAuth2 resource server validating the caller's own JWT against the api's
 * issuer, requiring {@code ROLE_DATAHUB_ACCESS}, and resolving the tenant from the token's
 * {@code organization} claim with the api's own {@link OrganizationValidator}. Unlike the analysis
 * service this one does tenant work: its tables live in each tenant's database.
 *
 * <p>Browser-facing (the console's Flows section calls it directly), so CORS is enabled for the
 * console origin.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

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

    /**
     * Where Keycloak sends the browser back after someone authorised a deployment. A browser
     * navigation carries no bearer token, so this one path is open, and has no tenant filter: the
     * signed {@code state} it carries names the tenant and is all it trusts (DeployAuthorization).
     */
    @Bean
    @Order(2)
    SecurityFilterChain deployCallbackFilterChain(HttpSecurity http) throws Exception {
        http
                .securityMatcher("/flows/deployment/callback")
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(AbstractHttpConfigurer::disable);
        return http.build();
    }

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http, TenantConfigService tenantConfigService,
            FlowSchemaCheck schemaCheck, JsonMapper jsonMapper,
            @Value("${cors.allowed-origins:http://localhost:8080}") String[] corsOrigins) throws Exception {
        http
                .cors(cors -> cors.configurationSource(corsConfigurationSource(corsOrigins)))
                .authorizeHttpRequests(auth -> auth.anyRequest().hasRole("DATAHUB_ACCESS"))
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter())))
                // After authorization: by then the token is validated, OrganizationValidator has set
                // TenantContext, and an unauthenticated request has already been turned away.
                .addFilterAfter(new FlowTenantFilter(tenantConfigService, schemaCheck, jsonMapper),
                        AuthorizationFilter.class)
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(AbstractHttpConfigurer::disable);
        return http.build();
    }

    @Bean
    JwtDecoder jwtDecoder(
            @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}") String issuerUri) {
        NimbusJwtDecoder decoder = JwtDecoders.fromIssuerLocation(issuerUri);
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(issuerUri), new OrganizationValidator()));
        return decoder;
    }

    /**
     * Clears {@link TenantContext} at the end of every request, first in and last out, so that one
     * request's tenant never stays on a pooled thread for the next. The api's
     * {@code RequestStateCleanupFilter} does the same.
     */
    @Bean
    FilterRegistrationBean<Filter> tenantContextCleanupFilter() {
        FilterRegistrationBean<Filter> registration = new FilterRegistrationBean<>((request, response, chain) -> {
            try {
                chain.doFilter(request, response);
            } finally {
                TenantContext.clear();
            }
        });
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }

    private static CorsConfigurationSource corsConfigurationSource(String[] origins) {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(Arrays.asList(origins));
        configuration.setAllowedMethods(List.of("*"));
        configuration.setAllowedHeaders(List.of("*"));
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    /** Keycloak roles live in the {@code realm_access.roles} claim; map them to {@code ROLE_*} (same as the api). */
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
}
