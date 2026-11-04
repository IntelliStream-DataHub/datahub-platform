// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.flow.identity;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * datahub-api's {@code GET /me/grants} and {@code GET /me/datasets?access=write}, answering for
 * whichever bearer token asks; a token it does not know gets a 401.
 */
final class FakeMe implements AutoCloseable {

    /** What one token may do. */
    record Grants(boolean admin, List<String> groups, boolean writeAll, List<Long> writable) {
    }

    private final HttpServer server;
    final Map<String, Grants> byToken = new ConcurrentHashMap<>();

    FakeMe() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/me/", this::handle);
        server.start();
    }

    String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private void handle(HttpExchange exchange) throws IOException {
        String auth = exchange.getRequestHeaders().getFirst("Authorization");
        Grants grants = auth == null ? null : byToken.get(auth.substring("Bearer ".length()));
        if (grants == null) {
            respond(exchange, 401, "{\"status\":401}");
            return;
        }
        String body;
        if (exchange.getRequestURI().getPath().endsWith("/grants")) {
            body = "{\"admin\":" + grants.admin() + ",\"groups\":[" + grants.groups().stream()
                    .map(g -> "\"" + g + "\"").collect(Collectors.joining(",")) + "]}";
        } else {
            body = "{\"all\":" + grants.writeAll() + ",\"ids\":[" + grants.writable().stream()
                    .map(id -> "\"" + id + "\"").collect(Collectors.joining(",")) + "]}";
        }
        respond(exchange, 200, body);
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", status == 200 ? "application/json"
                : "application/problem+json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
