// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.analysis.config;

import ai.intellistream.datahub.sdk.client.DatahubClient;
import ai.intellistream.datahub.sdk.client.DatahubConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

import java.net.http.HttpClient;

/**
 * Builds a {@link DatahubClient} that calls the api <em>as the current caller</em>: it reads the JWT
 * the browser presented (validated by this service's resource-server {@link SecurityConfig}) from the
 * security context and hands it to the SDK as a static token, so every api call the analysis makes
 * carries the user's identity and the api's per-dataset ACLs apply unchanged.
 *
 * <p>One {@link HttpClient} is shared across requests (its connection pool / executor is reused); only
 * the cheap per-token {@link DatahubClient} wrapper is built per request, since the SDK bakes the
 * token into the client and offers no per-call override.
 */
@Component
public class AnalysisApiClientFactory {

    private final String apiBaseUrl;
    private final HttpClient httpClient = HttpClient.newHttpClient();

    public AnalysisApiClientFactory(@Value("${datahub.api.url:http://localhost:8081}") String apiBaseUrl) {
        this.apiBaseUrl = apiBaseUrl;
    }

    /** A client bound to the current request's user JWT — forwards it to the api on every call. */
    public DatahubClient forCurrentUser() {
        DatahubConfig config = DatahubConfig.builder()
                .baseUrl(apiBaseUrl)
                .token(currentJwt())
                .build();
        return DatahubClient.create(config, httpClient);
    }

    /** The raw JWT the caller presented to this service, to forward to the api. */
    private static String currentJwt() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof JwtAuthenticationToken jwtAuth) {
            return jwtAuth.getToken().getTokenValue();
        }
        throw new IllegalStateException("no JWT in the security context to forward to the datahub api");
    }
}
