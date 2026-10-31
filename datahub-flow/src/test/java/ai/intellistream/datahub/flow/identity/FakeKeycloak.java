// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.flow.identity;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A Keycloak realm's discovery document, token endpoint and revocation endpoint, answering what a
 * test tells it to and recording what it was asked.
 */
final class FakeKeycloak implements AutoCloseable {

    /** One request to the token or revocation endpoint: its Basic credentials and form fields. */
    record Request(String path, String authorization, Map<String, String> form) {
    }

    private final HttpServer server;
    final List<Request> requests = new CopyOnWriteArrayList<>();
    volatile int tokenStatus = 200;
    volatile String tokenBody = token("access-1", "offline-1", 300);

    FakeKeycloak() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/realms/datahub/.well-known/openid-configuration", exchange -> respond(exchange,
                200, """
                        {"issuer":"%1$s","authorization_endpoint":"%1$s/protocol/openid-connect/auth",
                         "token_endpoint":"%1$s/protocol/openid-connect/token",
                         "revocation_endpoint":"%1$s/protocol/openid-connect/revoke"}
                        """.formatted(issuer())));
        server.createContext("/realms/datahub/protocol/openid-connect/token", exchange -> {
            record(exchange);
            respond(exchange, tokenStatus, tokenBody);
        });
        server.createContext("/realms/datahub/protocol/openid-connect/revoke", exchange -> {
            record(exchange);
            respond(exchange, 200, "");
        });
        server.start();
    }

    String issuer() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/realms/datahub";
    }

    static String token(String access, String refresh, int expiresIn) {
        return "{\"access_token\":\"" + access + "\","
                + (refresh == null ? "" : "\"refresh_token\":\"" + refresh + "\",")
                + "\"expires_in\":" + expiresIn + ",\"token_type\":\"Bearer\"}";
    }

    List<Request> requests(String pathSuffix) {
        return requests.stream().filter(r -> r.path().endsWith(pathSuffix)).toList();
    }

    private void record(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Map<String, String> form = new LinkedHashMap<>();
        for (String pair : body.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                form.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                        URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
            }
        }
        requests.add(new Request(exchange.getRequestURI().getPath(),
                exchange.getRequestHeaders().getFirst("Authorization"), form));
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            exchange.getResponseBody().write(bytes);
        }
        exchange.close();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
