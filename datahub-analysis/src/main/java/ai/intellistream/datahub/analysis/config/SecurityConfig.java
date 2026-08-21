// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.analysis.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The trust boundary for this service. It is an OAuth2 resource server that validates the
 * <em>user's own JWT</em> — the same token the browser presents to {@code /analysis} (and which this
 * service forwards to the api when gathering data) — against the same issuer the api uses
 * ({@code spring.security.oauth2.resourceserver.jwt.issuer-uri}), and requires
 * {@code ROLE_DATAHUB_ACCESS}, mirroring the api's gate.
 *
 * <p>It does <strong>no</strong> tenant/ACL work of its own: the api enforces per-dataset ACLs on the
 * data it serves back. This only confirms the caller holds a valid, authorized token — so there is no
 * separate shared secret to provision. Because the browser posts here directly (a different origin
 * from this service), CORS is enabled for the console origin.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http,
            @Value("${cors.allowed-origins:http://localhost:8080}") String[] corsOrigins) throws Exception {
        http
                // Browser-facing: the console posts to /analysis from its own origin, so allow it.
                .cors(cors -> cors.configurationSource(corsConfigurationSource(corsOrigins)))
                .authorizeHttpRequests(auth -> auth.anyRequest().hasRole("DATAHUB_ACCESS"))
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter())))
                // Stateless: the token is the whole story, no session, so CSRF doesn't apply.
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(AbstractHttpConfigurer::disable);
        return http.build();
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
