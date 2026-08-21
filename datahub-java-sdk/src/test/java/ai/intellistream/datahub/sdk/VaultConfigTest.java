// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.sdk;

import ai.intellistream.datahub.sdk.client.DatahubConfig;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VaultConfigTest {

    @Test
    void readsConfigFromVaultKvV2() throws Exception {
        AtomicReference<String> vaultToken = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String secret = "{\"data\":{\"data\":{"
                + "\"BASE_URL\":\"https://api.example.com\","
                + "\"CLIENT_ID\":\"svc\",\"CLIENT_SECRET\":\"sshh\","
                + "\"TOKEN_URI\":\"https://auth.example.com/token\""
                + "},\"metadata\":{\"version\":1}}}";
        server.createContext("/v1/secret/data/datahub/sdk", exchange -> {
            vaultToken.set(exchange.getRequestHeaders().getFirst("X-Vault-Token"));
            byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (var os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        server.start();
        try {
            String address = "http://127.0.0.1:" + server.getAddress().getPort();
            DatahubConfig config = DatahubConfig.fromVault(address, "vault-test-token", "datahub/sdk");

            assertEquals("https://api.example.com", config.baseUrl());
            assertTrue(config.hasClientCredentials());
            assertFalse(config.hasStaticToken());
            assertEquals("svc", config.clientId());
            assertEquals("vault-test-token", vaultToken.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void readsConfigFromVaultViaAppRole() throws Exception {
        AtomicReference<String> loginBody = new AtomicReference<>();
        AtomicReference<String> readToken = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);

        server.createContext("/v1/auth/approle/login", exchange -> {
            loginBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] bytes = "{\"auth\":{\"client_token\":\"s.login-token\",\"lease_duration\":3600}}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (var os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        server.createContext("/v1/secret/data/datahub/sdk", exchange -> {
            readToken.set(exchange.getRequestHeaders().getFirst("X-Vault-Token"));
            byte[] bytes = "{\"data\":{\"data\":{\"BASE_URL\":\"https://api.example.com\",\"TOKEN\":\"static-tok\"}}}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (var os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        server.start();
        try {
            String address = "http://127.0.0.1:" + server.getAddress().getPort();
            DatahubConfig config = DatahubConfig.fromVaultAppRole(address, "role-123", "secret-456", "datahub/sdk");

            assertEquals("https://api.example.com", config.baseUrl());
            assertTrue(config.hasStaticToken());
            // logged in with the role/secret, then used the returned token to read the secret
            assertTrue(loginBody.get().contains("role-123"));
            assertTrue(loginBody.get().contains("secret-456"));
            assertEquals("s.login-token", readToken.get());
        } finally {
            server.stop(0);
        }
    }
}
