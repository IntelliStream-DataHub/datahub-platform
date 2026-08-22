// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.config;

import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsParameters;
import com.sun.net.httpserver.HttpsServer;
import io.github.jopenlibs.vault.Vault;
import io.github.jopenlibs.vault.VaultConfig;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.TrustManagerFactory;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Proves {@code vault.keystore} / {@code vault.truststore} produce an SSL context that completes a
 * mutual-TLS handshake, for both the Vault driver and the plain {@link HttpClient} path the tenant
 * refresh uses. Stands up a JDK {@link HttpsServer} that requires a client certificate, backed by
 * throwaway PKCS12 stores generated with the JDK's own {@code keytool}, so no key material is
 * checked in.
 */
class VaultClientFactoryTlsTest {

    private static final String PASSWORD = "changeit";
    private static final String HEALTH_BODY =
            "{\"initialized\":true,\"sealed\":false,\"standby\":false,\"performance_standby\":false,"
                    + "\"replication_performance_mode\":\"disabled\",\"replication_dr_mode\":\"disabled\","
                    + "\"server_time_utc\":1700000000,\"version\":\"1.18.0\",\"cluster_name\":\"test\",\"cluster_id\":\"test\"}";

    @TempDir
    static Path dir;

    static Path serverStore;
    static Path clientStore;
    static Path trustStore;
    static HttpsServer server;
    static String baseUrl;

    @BeforeAll
    static void startMutualTlsServer() throws Exception {
        Path keytool = Path.of(System.getProperty("java.home"), "bin", "keytool");
        assumeTrue(Files.isExecutable(keytool), "keytool not available at " + keytool);

        serverStore = dir.resolve("server.p12");
        clientStore = dir.resolve("client.p12");
        trustStore = dir.resolve("trust.p12");
        keytool(keytool, "-genkeypair", "-alias", "server", "-keyalg", "EC", "-validity", "1",
                "-dname", "CN=localhost", "-ext", "SAN=dns:localhost,ip:127.0.0.1",
                "-storetype", "PKCS12", "-keystore", serverStore.toString(), "-storepass", PASSWORD);
        keytool(keytool, "-genkeypair", "-alias", "client", "-keyalg", "EC", "-validity", "1",
                "-dname", "CN=datahub-client",
                "-storetype", "PKCS12", "-keystore", clientStore.toString(), "-storepass", PASSWORD);
        // Self-signed on both sides, so each certificate is its own CA: one truststore holding both
        // lets the server verify the client and the client verify the server.
        for (String alias : List.of("server", "client")) {
            Path cert = dir.resolve(alias + ".crt");
            keytool(keytool, "-exportcert", "-alias", alias, "-keystore", dir.resolve(alias + ".p12").toString(),
                    "-storepass", PASSWORD, "-rfc", "-file", cert.toString());
            keytool(keytool, "-importcert", "-noprompt", "-alias", alias, "-file", cert.toString(),
                    "-storetype", "PKCS12", "-keystore", trustStore.toString(), "-storepass", PASSWORD);
        }

        SSLContext serverContext = serverSslContext();
        server = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(serverContext) {
            @Override
            public void configure(HttpsParameters params) {
                var parameters = getSSLContext().getDefaultSSLParameters();
                parameters.setNeedClientAuth(true);
                params.setSSLParameters(parameters);
            }
        });
        server.createContext("/v1/sys/health", exchange -> {
            byte[] body = HEALTH_BODY.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (var out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        baseUrl = "https://localhost:" + server.getAddress().getPort();
    }

    @AfterAll
    static void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void keystoreAndTruststoreCompleteTheMutualTlsHandshake() throws Exception {
        var ssl = VaultClientFactory.sslContext(tls(clientStore, trustStore));

        assertThat(ssl).isPresent();
        HttpResponse<String> response = HttpClient.newBuilder().sslContext(ssl.get()).build()
                .send(HttpRequest.newBuilder(URI.create(baseUrl + "/v1/sys/health")).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"sealed\":false");
    }

    @Test
    void vaultDriverUsesTheSameSslSettings() throws Exception {
        var sslConfig = VaultClientFactory.sslConfig(tls(clientStore, trustStore)).orElseThrow();
        var config = new VaultConfig().address(baseUrl).engineVersion(2).sslConfig(sslConfig).build();

        var health = Vault.create(config).debug().health();

        assertThat(health.getRestResponse().getStatus()).isEqualTo(200);
        assertThat(health.getSealed()).isFalse();
    }

    @Test
    void withoutAClientCertificateTheServerRejectsTheHandshake() {
        var ssl = VaultClientFactory.sslContext(tls(null, trustStore)).orElseThrow();
        var client = HttpClient.newBuilder().sslContext(ssl).build();
        var request = HttpRequest.newBuilder(URI.create(baseUrl + "/v1/sys/health")).GET().build();

        assertThatThrownBy(() -> client.send(request, HttpResponse.BodyHandlers.ofString()))
                .isInstanceOf(IOException.class)
                .satisfies(e -> assertThat(e instanceof SSLHandshakeException || e.getCause() instanceof SSLHandshakeException
                        || String.valueOf(e.getMessage()).contains("SSL")).isTrue());
    }

    @Test
    void jksTruststoreIsRecognisedByExtension() throws Exception {
        Path jks = dir.resolve("trust.jks");
        Path keytool = Path.of(System.getProperty("java.home"), "bin", "keytool");
        keytool(keytool, "-importkeystore", "-noprompt", "-srckeystore", trustStore.toString(), "-srcstoretype", "PKCS12",
                "-srcstorepass", PASSWORD, "-destkeystore", jks.toString(), "-deststoretype", "JKS", "-deststorepass", PASSWORD);

        var ssl = VaultClientFactory.sslContext(tls(clientStore, jks));

        HttpResponse<String> response = HttpClient.newBuilder().sslContext(ssl.orElseThrow()).build()
                .send(HttpRequest.newBuilder(URI.create(baseUrl + "/v1/sys/health")).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
    }

    @Test
    void noTlsSettingsMeansNoCustomContext() {
        assertThat(VaultClientFactory.sslConfig(VaultProperties.of(baseUrl, "role", "secret"))).isEmpty();
        assertThat(VaultClientFactory.sslContext(VaultProperties.of(baseUrl, "role", "secret"))).isEmpty();
    }

    @Test
    void wrongPasswordNamesTheStore() {
        var props = new VaultProperties(baseUrl, "role", "secret", "mount",
                clientStore.toString(), "not-the-password", null, "");

        assertThatThrownBy(() -> VaultClientFactory.sslConfig(props))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("vault.keystore")
                .hasMessageContaining("client.p12");
    }

    @Test
    void missingFileNamesThePath() {
        var props = new VaultProperties(baseUrl, "role", "secret", "mount",
                dir.resolve("nope.p12").toString(), "", null, "");

        assertThatThrownBy(() -> VaultClientFactory.sslConfig(props))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("vault.keystore")
                .hasMessageContaining("nope.p12")
                .hasMessageContaining("does not exist");
    }

    private static VaultProperties tls(Path keystore, Path truststore) {
        return new VaultProperties(baseUrl, "role", "secret", "mount",
                keystore == null ? null : keystore.toString(), PASSWORD,
                truststore == null ? null : truststore.toString(), PASSWORD);
    }

    private static SSLContext serverSslContext() throws Exception {
        KeyStore keys = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(serverStore)) {
            keys.load(in, PASSWORD.toCharArray());
        }
        KeyStore trust = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(trustStore)) {
            trust.load(in, PASSWORD.toCharArray());
        }
        var kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(keys, PASSWORD.toCharArray());
        var tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(trust);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(kmf.getKeyManagers(), tmf.getTrustManagers(), null);
        return context;
    }

    private static void keytool(Path keytool, String... args) throws Exception {
        var command = new java.util.ArrayList<String>();
        command.add(keytool.toString());
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (process.waitFor() != 0) {
            throw new IllegalStateException("keytool " + String.join(" ", args) + " failed:\n" + output);
        }
    }
}
