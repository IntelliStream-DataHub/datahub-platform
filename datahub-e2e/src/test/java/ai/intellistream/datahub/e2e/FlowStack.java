// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.e2e;

import org.junit.jupiter.api.Assumptions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The flow service as the e2e tests reach it: tokens for one or two tenants, the plain HTTP calls
 * the flows API takes (the Java SDK has no flows client), the organization groups the test clients
 * need, and, when the tests are to kill it, the service itself as a child process.
 *
 * <p>Tenant one is the stack's {@code DATAHUB_CLIENT_ID}; tenant two, for the isolation test, is
 * {@code DATAHUB_OTHER_CLIENT_ID}/{@code DATAHUB_OTHER_CLIENT_SECRET}, a service client of another
 * organization. A service client only holds dataset grants by default (see
 * {@code deploy/keycloak/bootstrap-org-groups.sh}), so {@link #grantFlowGroups} adds the three
 * flow groups through the Keycloak admin API, with {@code KEYCLOAK_ADMIN_USER}/{@code
 * KEYCLOAK_ADMIN_PASSWORD} (default {@code admin}/{@code admin}, the dev realm's) at the Keycloak
 * that issues the tokens.
 */
final class FlowStack {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Duration TOKEN_REUSE = Duration.ofSeconds(60);
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    private FlowStack() {
    }

    /** A tenant's service client: takes and caches its tokens, and knows its organization alias. */
    static final class Client {
        final String clientId;
        private final String secret;
        private String token;
        private Instant tokenTakenAt = Instant.EPOCH;

        Client(String clientId, String secret) {
            this.clientId = clientId;
            this.secret = secret;
        }

        synchronized String token() {
            if (Instant.now().isAfter(tokenTakenAt.plus(TOKEN_REUSE))) {
                token = clientCredentials(clientId, secret, LiveStack.env("DATAHUB_SCOPE", "openid organization:*"));
                tokenTakenAt = Instant.now();
            }
            return token;
        }

        /** The organization alias in the token's claim: what the tenant is called in Keycloak. */
        String organization() {
            String[] parts = token().split("\\.");
            JsonNode payload = JSON.readTree(Base64.getUrlDecoder().decode(parts[1]));
            JsonNode organization = payload.path("organization");
            if (!organization.isObject() || organization.isEmpty()) {
                throw new IllegalStateException("The token of " + clientId + " carries no organization claim; "
                        + "the scope must select one (DATAHUB_SCOPE)");
            }
            return organization.propertyNames().iterator().next();
        }

        String serviceAccountUsername() {
            return "service-account-" + clientId;
        }
    }

    static Client tenantOne() {
        return new Client(require("DATAHUB_CLIENT_ID"), require("DATAHUB_CLIENT_SECRET"));
    }

    /** The second tenant's client, or null when none is configured. */
    static Client tenantTwo() {
        String id = LiveStack.env("DATAHUB_OTHER_CLIENT_ID", null);
        String secret = LiveStack.env("DATAHUB_OTHER_CLIENT_SECRET", null);
        return id == null || secret == null ? null : new Client(id, secret);
    }

    static String apiUrl() {
        return LiveStack.baseUrl();
    }

    // ---------------------------------------------------------------- Keycloak

    private static String clientCredentials(String clientId, String secret, String scope) {
        String form = "grant_type=client_credentials&client_id=" + encode(clientId) + "&client_secret="
                + encode(secret) + "&scope=" + encode(scope);
        Response response = send(HttpRequest.newBuilder(URI.create(require("DATAHUB_TOKEN_URI")))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form)).build());
        if (response.status != 200) {
            throw new IllegalStateException("Keycloak refused client " + clientId + ": " + response.status
                    + " " + response.body);
        }
        return response.body.get("access_token").asString();
    }

    /**
     * Puts the client's service account into the flow groups of its organization: {@code
     * /flows/read}, {@code /flows/write} (to save and deploy flows) and {@code /settings/flow/write}
     * (to register itself as the client the runs act as). Idempotent. Skips the tests when the
     * admin API cannot be used, saying what to grant by hand.
     */
    static void grantFlowGroups(Client client) {
        URI tokenUri = URI.create(require("DATAHUB_TOKEN_URI"));
        Matcher realm = Pattern.compile("/realms/([^/]+)/").matcher(tokenUri.getPath());
        if (!realm.find()) {
            Assumptions.abort("DATAHUB_TOKEN_URI does not name a Keycloak realm; grant the flow groups by hand");
        }
        String keycloak = LiveStack.env("KEYCLOAK_ADMIN_URL", tokenUri.getScheme() + "://" + tokenUri.getAuthority());
        String admin = LiveStack.env("KEYCLOAK_ADMIN_USER", "admin");
        String password = LiveStack.env("KEYCLOAK_ADMIN_PASSWORD", "admin");
        List<String> groups = List.of("/flows/read", "/flows/write", "/settings/flow/write");
        String organization = client.organization();
        String username = client.serviceAccountUsername();

        Response login = send(HttpRequest.newBuilder(URI.create(keycloak + "/realms/master/protocol/openid-connect/token"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("grant_type=password&client_id=admin-cli&username="
                        + encode(admin) + "&password=" + encode(password))).build());
        Assumptions.assumeTrue(login.status == 200, () -> "Keycloak admin login at " + keycloak + " answered "
                + login.status + "; set KEYCLOAK_ADMIN_USER/KEYCLOAK_ADMIN_PASSWORD, or put " + username
                + " into " + groups + " of organization " + organization + " yourself (see "
                + "deploy/keycloak/bootstrap-org-groups.sh)");
        String adminToken = login.body.get("access_token").asString();
        String base = keycloak + "/admin/realms/" + realm.group(1);

        JsonNode organizations = admin(adminToken, base + "/organizations?search=" + encode(organization));
        String orgId = null;
        for (JsonNode o : organizations) {
            if (organization.equals(o.path("alias").asString())) {
                orgId = o.get("id").asString();
            }
        }
        JsonNode users = admin(adminToken, base + "/users?exact=true&username=" + encode(username));
        String userId = users.isEmpty() ? null : users.get(0).get("id").asString();
        Assumptions.assumeTrue(orgId != null && userId != null, "No organization " + organization + " or no user "
                + username + " in realm " + realm.group(1));

        for (String path : groups) {
            String groupId = null;
            String query = base + "/organizations/" + orgId + "/groups";
            for (String segment : path.substring(1).split("/")) {
                JsonNode children = admin(adminToken, query);
                groupId = null;
                for (JsonNode g : children) {
                    if (segment.equals(g.path("name").asString())) {
                        groupId = g.get("id").asString();
                    }
                }
                Assumptions.assumeTrue(groupId != null, "Organization " + organization + " has no group " + path
                        + "; run deploy/keycloak/bootstrap-org-groups.sh against this Keycloak first");
                query = base + "/organizations/" + orgId + "/groups/" + groupId + "/children";
            }
            Response put = send(HttpRequest.newBuilder(URI.create(base + "/organizations/" + orgId + "/groups/"
                    + groupId + "/members/" + userId)).header("Authorization", "Bearer " + adminToken)
                    .PUT(HttpRequest.BodyPublishers.noBody()).build());
            if (put.status / 100 != 2) {
                throw new IllegalStateException("Granting " + path + " to " + username + " answered " + put.status
                        + " " + put.body);
            }
        }
    }

    private static JsonNode admin(String token, String url) {
        Response response = send(HttpRequest.newBuilder(URI.create(url)).header("Authorization", "Bearer " + token)
                .GET().build());
        if (response.status != 200) {
            throw new IllegalStateException("Keycloak admin GET " + url + " answered " + response.status + " "
                    + response.body);
        }
        return response.body;
    }

    // ---------------------------------------------------------------- HTTP

    record Response(int status, JsonNode body) {
        JsonNode items() {
            return body.path("items");
        }
    }

    static Response get(String url, Client as) {
        return send(request(url, as).GET().build());
    }

    static Response post(String url, Client as, String body) {
        return send(request(url, as).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body == null ? "" : body)).build());
    }

    static Response put(String url, Client as, String body) {
        return send(request(url, as).header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(body)).build());
    }

    static Response delete(String url, Client as) {
        return send(request(url, as).DELETE().build());
    }

    private static HttpRequest.Builder request(String url, Client as) {
        return HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + as.token());
    }

    private static Response send(HttpRequest request) {
        try {
            HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
            String text = response.body();
            JsonNode body;
            try {
                body = text == null || text.isBlank() ? JSON.nullNode() : JSON.readTree(text);
            } catch (RuntimeException notJson) {
                body = JSON.getNodeFactory().textNode(text);
            }
            return new Response(response.statusCode(), body);
        } catch (IOException e) {
            throw new IllegalStateException(request.method() + " " + request.uri() + " failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted", e);
        }
    }

    static String json(Object value) {
        return JSON.writeValueAsString(value);
    }

    static JsonMapper mapper() {
        return JSON;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String require(String name) {
        String value = LiveStack.env(name, null);
        if (value == null) {
            throw new IllegalStateException(name + " is not set; see datahub-e2e/README.md");
        }
        return value;
    }

    // ---------------------------------------------------------------- the service as a process

    /**
     * The flow service started from its boot jar ({@code DATAHUB_FLOW_JAR}, default the one
     * {@code ./gradlew :datahub-flow:bootJar} builds) on a free port, with its loops sped up so that
     * a crash is noticed in seconds rather than minutes: a 6 s lease, renewed every 2 s, reaped
     * every 3 s, swept every 2 s. Started by {@link #start()}, killed outright by {@link #kill()},
     * as a crash would, and stopped cleanly by {@link #stop()}.
     */
    static final class Service {
        private static final Duration START_TIMEOUT = Duration.ofMinutes(3);

        private final Path jar;
        private final List<String> extraArgs;
        private Process process;
        private String url;
        private Thread log;

        Service(Path jar, List<String> extraArgs) {
            this.jar = jar;
            this.extraArgs = extraArgs;
        }

        /** The jar to run, or null when none is configured or built. */
        static Path jar() {
            String configured = LiveStack.env("DATAHUB_FLOW_JAR", null);
            if (configured != null) {
                return Path.of(configured);
            }
            Path libs = Path.of("..", "datahub-flow", "build", "libs");
            if (!Files.isDirectory(libs)) {
                return null;
            }
            try (Stream<Path> files = Files.list(libs)) {
                return files.filter(p -> p.getFileName().toString().matches("datahub-flow-.*\\.jar"))
                        .filter(p -> !p.getFileName().toString().endsWith("-plain.jar"))
                        .findFirst().orElse(null);
            } catch (IOException e) {
                return null;
            }
        }

        String url() {
            return url;
        }

        boolean alive() {
            return process != null && process.isAlive();
        }

        void start() throws IOException {
            List<String> command = new ArrayList<>(List.of(
                    ProcessHandle.current().info().command().orElse("java"),
                    "-Xmx2g",
                    "-jar", jar.toAbsolutePath().toString(),
                    "--spring.profiles.active=" + LiveStack.env("DATAHUB_FLOW_PROFILES", "dev"),
                    "--server.port=0",
                    // No management server: it would log a second "Tomcat started on port" line.
                    "--management.server.port=-1",
                    "--datahub.api.url=" + apiUrl(),
                    "--datahub.flow.runs.lease=PT6S",
                    "--datahub.flow.runs.heartbeat-interval=PT2S",
                    "--datahub.flow.runs.reaper-interval=PT3S",
                    "--datahub.flow.runs.sweep-interval=PT2S",
                    "--datahub.flow.runs.request-timeout=PT3S",
                    "--datahub.flow.runs.backoff=PT1S",
                    "--datahub.flow.records.ack-wait=PT5S"));
            command.addAll(extraArgs);
            process = new ProcessBuilder(command).redirectErrorStream(true).start();
            BufferedReader out = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
            Pattern port = Pattern.compile("Tomcat started on port (\\d+)");
            long until = System.nanoTime() + START_TIMEOUT.toNanos();
            String line;
            while ((line = out.readLine()) != null) {
                System.out.println("[flow] " + line);
                Matcher m = port.matcher(line);
                if (m.find()) {
                    url = "http://127.0.0.1:" + m.group(1);
                    break;
                }
                if (System.nanoTime() > until) {
                    kill();
                    throw new IllegalStateException("The flow service did not start within " + START_TIMEOUT);
                }
            }
            if (url == null) {
                throw new IllegalStateException("The flow service exited before listening; see its log above");
            }
            // Keep draining its output so it never blocks on a full pipe.
            log = Thread.ofVirtual().start(() -> {
                try {
                    String l;
                    while ((l = out.readLine()) != null) {
                        System.out.println("[flow] " + l);
                    }
                } catch (IOException ignored) {
                    // the process ended
                }
            });
            awaitAnswering();
        }

        private void awaitAnswering() {
            long until = System.nanoTime() + Duration.ofSeconds(30).toNanos();
            while (System.nanoTime() < until) {
                try {
                    HttpResponse<Void> probe = HTTP.send(HttpRequest.newBuilder(URI.create(url + "/catalog/processors"))
                            .timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.discarding());
                    if (probe.statusCode() == 401) {
                        return;
                    }
                } catch (IOException | InterruptedException ignored) {
                    // not up yet
                }
                sleep(500);
            }
            throw new IllegalStateException("The flow service at " + url + " is not answering");
        }

        /** SIGKILL: no shutdown hook runs, no lease is handed back. */
        void kill() {
            if (process != null) {
                process.destroyForcibly();
                try {
                    process.waitFor(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        void stop() {
            if (process != null && process.isAlive()) {
                process.destroy();
                try {
                    if (!process.waitFor(40, TimeUnit.SECONDS)) {
                        process.destroyForcibly();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted", e);
        }
    }

    static Map<String, Object> map(Object... keysAndValues) {
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            out.put((String) keysAndValues[i], keysAndValues[i + 1]);
        }
        return out;
    }
}
