// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.sdk;

import ai.intellistream.datahub.sdk.auth.TokenProvider;
import ai.intellistream.datahub.sdk.client.DatahubConfig;
import ai.intellistream.datahub.sdk.client.DatahubConfigException;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TokenProviderTest {

    @Test
    void omitsScopeAndAudienceWhenUnset() throws Exception {
        withTokenEndpoint((baseUrl, lastBody) -> {
            DatahubConfig config = DatahubConfig.builder()
                    .baseUrl("https://api.example.com")
                    .clientCredentials("svc", "sshh", baseUrl + "/token")
                    .build();

            assertEquals("tok-1", newProvider(config).getToken());
            assertEquals("grant_type=client_credentials", lastBody.get());
        });
    }

    @Test
    void sendsScopeAndAudienceWhenSet() throws Exception {
        withTokenEndpoint((baseUrl, lastBody) -> {
            DatahubConfig config = DatahubConfig.builder()
                    .baseUrl("https://api.example.com")
                    .clientCredentials("svc", "sshh", baseUrl + "/token")
                    .scope("api://datahub/.default")
                    .audience("https://api.example.com")
                    .build();

            assertEquals("tok-1", newProvider(config).getToken());
            String body = lastBody.get();
            assertTrue(body.startsWith("grant_type=client_credentials"), body);
            assertTrue(body.contains("&scope=api%3A%2F%2Fdatahub%2F.default"), body);
            assertTrue(body.contains("&audience=https%3A%2F%2Fapi.example.com"), body);
        });
    }

    @Test
    void cachesTokenAcrossCalls() throws Exception {
        withTokenEndpoint((baseUrl, lastBody) -> {
            DatahubConfig config = DatahubConfig.builder()
                    .baseUrl("https://api.example.com")
                    .clientCredentials("svc", "sshh", baseUrl + "/token")
                    .build();

            TokenProvider provider = newProvider(config);
            assertEquals("tok-1", provider.getToken());
            // 3600s TTL minus the 30s skew is still far in the future — no second exchange.
            assertEquals("tok-1", provider.getToken());
        });
    }

    @Test
    void exchangesStaticAssertionWithJwtBearer() throws Exception {
        withTokenEndpoint((baseUrl, lastBody) -> {
            DatahubConfig config = DatahubConfig.builder()
                    .baseUrl("https://api.example.com")
                    .clientCredentials("datahub-jwt-grant", "kc-secret", baseUrl + "/token")
                    .assertion("header.payload.signature")
                    .build();

            assertEquals("tok-1", newProvider(config).getToken());
            String body = lastBody.get();
            assertTrue(body.startsWith("grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Ajwt-bearer"), body);
            assertTrue(body.contains("&assertion=header.payload.signature"), body);
        });
    }

    @Test
    void fetchesAssertionThenExchangesIt() throws Exception {
        AtomicReference<String> assertionBody = new AtomicReference<>();
        AtomicReference<String> assertionAuth = new AtomicReference<>();
        withTokenEndpoint((baseUrl, lastBody) -> {
            // /token issues "tok-N"; the assertion leg has its own endpoint issuing a fixed JWT.
            HttpServer entra = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            entra.createContext("/entra", exchange -> {
                assertionBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                assertionAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
                byte[] bytes = "{\"access_token\":\"entra.jwt.sig\",\"expires_in\":3600}"
                        .getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, bytes.length);
                try (var os = exchange.getResponseBody()) {
                    os.write(bytes);
                }
            });
            entra.start();
            try {
                String entraUrl = "http://127.0.0.1:" + entra.getAddress().getPort() + "/entra";
                DatahubConfig config = DatahubConfig.builder()
                        .baseUrl("https://api.example.com")
                        .clientCredentials("datahub-jwt-grant", "kc-secret", baseUrl + "/token")
                        .assertionCredentials("entra-app", "entra-secret", entraUrl)
                        .assertionScope("api://entra-app/.default")
                        .build();

                assertEquals("tok-1", newProvider(config).getToken());

                // leg 1: client credentials at the assertion provider, with its own scope and auth
                assertEquals("grant_type=client_credentials&scope=api%3A%2F%2Fentra-app%2F.default",
                        assertionBody.get());
                assertEquals("Basic " + base64("entra-app:entra-secret"), assertionAuth.get());

                // leg 2: the fetched JWT presented as the assertion
                assertTrue(lastBody.get().contains("&assertion=entra.jwt.sig"), lastBody.get());
            } finally {
                entra.stop(0);
            }
        });
    }

    @Test
    void rejectsIncompleteAssertionSource() {
        DatahubConfig.Builder builder = DatahubConfig.builder()
                .baseUrl("https://api.example.com")
                .clientCredentials("svc", "sshh", "https://auth.example.com/token")
                .assertionScope("api://entra-app/.default");

        DatahubConfigException e = assertThrows(DatahubConfigException.class, builder::build);
        assertTrue(e.getMessage().contains("incomplete jwt-bearer assertion source"), e.getMessage());
    }

    @Test
    void rejectsAssertionWithoutExchangeCredentials() {
        DatahubConfig.Builder builder = DatahubConfig.builder()
                .baseUrl("https://api.example.com")
                .token("static-tok")
                .assertion("header.payload.signature");

        DatahubConfigException e = assertThrows(DatahubConfigException.class, builder::build);
        assertTrue(e.getMessage().contains("jwt-bearer needs CLIENT_ID"), e.getMessage());
    }

    private static String base64(String s) {
        return java.util.Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

    private static TokenProvider newProvider(DatahubConfig config) {
        return new TokenProvider(config, HttpClient.newHttpClient(), JsonMapper.builder().build());
    }

    /** Starts a token endpoint that records the last form body and hands out {@code tok-<n>}. */
    private static void withTokenEndpoint(ThrowingBiConsumer body) throws Exception {
        AtomicReference<String> lastBody = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        int[] issued = {0};
        server.createContext("/token", exchange -> {
            lastBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] bytes = ("{\"access_token\":\"tok-" + (++issued[0]) + "\",\"expires_in\":3600}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (var os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        server.start();
        try {
            body.accept("http://127.0.0.1:" + server.getAddress().getPort(), lastBody);
        } finally {
            server.stop(0);
        }
    }

    @FunctionalInterface
    private interface ThrowingBiConsumer {
        void accept(String baseUrl, AtomicReference<String> lastBody) throws Exception;
    }
}
