// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.config;

import lombok.extern.slf4j.Slf4j;
import org.apache.pulsar.client.admin.PulsarAdmin;
import org.apache.pulsar.client.admin.PulsarAdminBuilder;
import org.apache.pulsar.client.api.*;
import org.apache.pulsar.client.impl.auth.oauth2.AuthenticationOAuth2;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.apache.commons.lang3.BooleanUtils.OFF;

@Configuration
@Slf4j
public class PulsarConfig {

    @Value("${pulsar.service.url}")
    private String pulsarUrl;

    @Value("${pulsar.service.httpUrl}")
    private String pulsarHttpUrl;

    @Value("${pulsar.admin-client-id:Null}")
    private String adminClientId;

    @Value("${pulsar.admin-client-secret:Null}")
    private String adminClientSecret;

    @Value("${pulsar.client-id}")
    private String clientId;

    @Value("${pulsar.client-secret:Null}")
    private String clientSecret;

    @Value("${pulsar.issuer-url:Null}")
    private String issuerUrl;

    @Value("${security.mode:off}")
    private String securityMode;

    // TLS + OAuth2 are on by default. Set to false for a local plaintext broker
    // (e.g. the docker-compose dev stack) that runs without mTLS or token auth.
    @Value("${datahub.pulsar.tls.enabled:true}")
    private boolean tlsEnabled;

    @Value("${datahub.pulsar.io-threads:8}")
    private int ioThreads;

    @Value("${datahub.pulsar.listener-threads:8}")
    private int listenerThreads;

    // Authoritative client-wide backpressure backstop (shared by all producers/consumers in the
    // JVM). Raised from 256 to 512 MB to give the datapoint producer room to buffer bursts; tune
    // per instance size via datahub.pulsar.memory-limit-mb.
    @Value("${datahub.pulsar.memory-limit-mb:512}")
    private int memoryLimitMb;

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Bean
    public PulsarClient pulsarClient() throws PulsarClientException {
        if (!securityMode.equalsIgnoreCase(OFF)) {
            return null;
        }
        ClientBuilder builder = PulsarClient.builder()
                .serviceUrl(pulsarUrl)
                .memoryLimit(memoryLimitMb, SizeUnit.MEGA_BYTES)
                .ioThreads(ioThreads)
                .listenerThreads(listenerThreads);
        if (tlsEnabled) {
            builder.tlsTrustCertsFilePath("ca.cert.pem")
                    .authentication(getAuthentication(clientId, clientSecret, "istream"));
        }
        return builder.build();
    }

    @Bean
    public PulsarAdmin pulsarAdmin() throws PulsarClientException {
        PulsarAdminBuilder builder = PulsarAdmin.builder()
                .serviceHttpUrl(pulsarHttpUrl);
        if (tlsEnabled) {
            builder.tlsTrustCertsFilePath("ca.cert.pem")
                    .authentication(getAuthentication(adminClientId, adminClientSecret, "pulsar-admin"));
        }
        return builder.build();
    }

    /**
     * Build the OAuth2 authentication for a Pulsar client. The credential JSON is constructed via
     * Jackson rather than string concatenation so that any character in the secret (quotes,
     * backslashes, control characters) is escaped correctly and cannot corrupt the JSON or smuggle
     * in extra fields. On failure the underlying PulsarClientException propagates — secret
     * material is never included in the message, which matters for log hygiene.
     */
    private Authentication getAuthentication(String clientId, String clientSecret, String audience)
            throws PulsarClientException.UnsupportedAuthenticationException {
        Map<String, String> credsMap = new LinkedHashMap<>();
        credsMap.put("type", "client_credentials");
        credsMap.put("client_id", clientId);
        credsMap.put("client_secret", clientSecret);
        credsMap.put("issuer_url", issuerUrl);
        String oauth2Creds = JSON.writeValueAsString(credsMap);
        String base64data = Base64.getEncoder().encodeToString(oauth2Creds.getBytes(StandardCharsets.UTF_8));

        Map<String, String> paramsMap = new LinkedHashMap<>();
        paramsMap.put("type", "client_credentials");
        paramsMap.put("privateKey", "data:application/json;base64," + base64data);
        paramsMap.put("issuerUrl", issuerUrl);
        paramsMap.put("audience", audience);
        String json = JSON.writeValueAsString(paramsMap);

        return AuthenticationFactory.create(AuthenticationOAuth2.class.getName(), json);
    }
}
