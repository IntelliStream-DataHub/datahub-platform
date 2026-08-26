// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.config;

import ai.intellistream.datahub.api.ApiDatahubApplication;
import ai.intellistream.datahub.api.init.pulsar.SubscriptionTopicProvisioner;
import ai.intellistream.datahub.clickhouse.ClickHouseClientPool;
import org.apache.pulsar.client.admin.PulsarAdmin;
import org.apache.pulsar.client.api.Producer;
import org.apache.pulsar.client.api.PulsarClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The scrape ships switched off.
 *
 * <p>It is unauthenticated by design, so anything that can reach the port reads request rates,
 * error rates, the endpoint inventory and JVM internals. That is a reasonable thing to serve to a
 * Prometheus host over mutual TLS, and an unreasonable thing to serve to whoever happens to reach
 * the port on a deployment that configured nothing. Shipping it off is what makes the default safe
 * without depending on a firewall rule being right.
 *
 * <p>Deliberately a context of its own: {@code SecurityFilterChainTest} turns the scrape on so its
 * deny-everything-else assertions mean something, and cannot also observe the shipped default.
 */
@SpringBootTest(
        classes = ApiDatahubApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("ctxtest")
class MetricsDisabledByDefaultTest {

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    @Value("${local.management.port}")
    private int managementPort;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @MockitoBean
    private PulsarClient pulsarClient;

    @MockitoBean
    private PulsarAdmin pulsarAdmin;

    @MockitoBean
    private ClickHouseClientPool clickHouseClientPool;

    @MockitoBean
    private SubscriptionTopicProvisioner subscriptionTopicProvisioner;

    @MockitoBean
    private InstanceLock instanceLock;

    @MockitoBean(name = "resourceMessageProducer")
    private Producer<?> resourceMessageProducer;

    @MockitoBean(name = "eventMessageProducer")
    private Producer<?> eventMessageProducer;

    @MockitoBean(name = "subscriptionNotifyProducer")
    private Producer<?> subscriptionNotifyProducer;

    @MockitoBean(name = "allDatapointProducer")
    private Producer<?> allDatapointProducer;

    @MockitoBean(name = "httpMessageProducer")
    private Producer<?> httpMessageProducer;

    @Test
    @DisplayName("The Prometheus scrape is not served unless a deployment turns it on")
    void scrapeIsNotExposedByDefault() {
        HttpResponse<String> response =
                send("http://localhost:" + managementPort + "/actuator/prometheus");

        assertThat(response.statusCode())
                .as("a deployment that configured nothing must not publish its metrics")
                .isNotEqualTo(HttpStatus.OK.value());
        assertThat(response.body()).doesNotContain("jvm_memory_used_bytes");
    }

    private HttpResponse<String> send(String url) {
        try {
            return HTTP.send(HttpRequest.newBuilder().uri(URI.create(url)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            throw new IllegalStateException("Request to " + url + " failed", e);
        }
    }
}
