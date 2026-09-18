// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.controllers;

import ai.intellistream.datahub.api.ApiDatahubApplication;
import ai.intellistream.datahub.api.datasecurity.DataSecurity;
import ai.intellistream.datahub.api.init.pulsar.SubscriptionTopicProvisioner;
import ai.intellistream.datahub.api.services.IngestQuotaService;
import ai.intellistream.datahub.api.services.TimeseriesService;
import ai.intellistream.datahub.clickhouse.ClickHouseClientPool;
import ai.intellistream.datahub.config.InstanceLock;
import ai.intellistream.datahub.tenant.Tenant;
import ai.intellistream.datahub.tenant.TenantConfigService;
import org.apache.pulsar.client.admin.PulsarAdmin;
import org.apache.pulsar.client.api.Producer;
import org.apache.pulsar.client.api.PulsarClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code POST /timeseries/data} over real HTTP, in the booted application: the production filter
 * chain and the message converters Spring Boot actually assembled, which together decide the status
 * a caller sees. The mocks are the start-up network clients, as in {@link DatapointBinaryHttpTest}.
 */
@SpringBootTest(classes = ApiDatahubApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("ctxtest")
class DatapointInsertHttpTest {

    /** Any string does: the JwtDecoder is a mock, and this is the value it is told to accept. */
    private static final String FAKE_BEARER = "dummy-bearer-insert-http";
    private static final String TENANT = "tenant-insert-http";

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private TenantConfigService tenantConfigService;

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

    @MockitoBean(name = "eventMessageProducer")
    private Producer<?> eventMessageProducer;

    @MockitoBean(name = "subscriptionNotifyProducer")
    private Producer<?> subscriptionNotifyProducer;

    @MockitoBean(name = "allDatapointProducer")
    private Producer<?> allDatapointProducer;

    @MockitoBean(name = "allDatapointBlockProducer")
    private Producer<?> allDatapointBlockProducer;

    @MockitoBean(name = "httpMessageProducer")
    private Producer<?> httpMessageProducer;

    @MockitoBean
    private DataSecurity dataSecurity;

    @MockitoBean
    private IngestQuotaService ingestQuota;

    @MockitoBean
    private TimeseriesService timeseriesService;

    @BeforeEach
    void setUp() {
        when(jwtDecoder.decode(FAKE_BEARER)).thenReturn(Jwt.withTokenValue(FAKE_BEARER)
                .header("alg", "none")
                .claim("organization", Map.of("org", Map.of("id", TENANT)))
                .claim("realm_access", Map.of("roles", List.of("DATAHUB_ACCESS")))
                .subject("insert-http-test")
                .build());
        tenantConfigService.cachedTenants.put(TENANT, new Tenant());
    }

    /**
     * The controller answers every {@link Exception} itself, so what escapes it is an {@link Error},
     * and the likeliest one under a large concurrent ingest is running out of heap. The body-cache
     * filter used to swallow it: nothing had set a status, so the caller was told {@code 200} and
     * every SDK counted the datapoints as stored.
     */
    @Test
    void anInsertThatRunsOutOfMemoryIsAServerErrorNotA200() throws Exception {
        when(timeseriesService.insertDatapoints(any())).thenThrow(new OutOfMemoryError("Java heap space"));

        HttpResponse<String> response = post("""
                {"items":[{"externalId":"sensor_a",
                           "datapoints":[{"timestamp":1745328000000,"value":"22.4"}]}]}""");

        // A status the SDKs retry. A 200 here tells the caller the datapoints were stored.
        assertThat(response.statusCode()).as(response.body()).isEqualTo(500);
    }

    /**
     * Strictness is attached by {@code StrictRequestBodyConfig} to the list Spring Boot builds, so
     * only a booted application shows it survived: a unit test of the config cannot tell whether
     * Boot's converter assembly still calls it.
     */
    @Test
    void anUnknownFieldInTheBodyIsA400NamingIt() throws Exception {
        HttpResponse<String> response = post("""
                {"items":[{"externalId":"sensor_a", "tableEngine":"MERGETREE",
                           "datapoints":[{"timestamp":1745328000000,"value":"22.4"}]}]}""");

        assertThat(response.statusCode()).as(response.body()).isEqualTo(400);
        assertThat(response.body()).contains("tableEngine");
        verify(timeseriesService, never()).insertDatapoints(any());
    }

    private HttpResponse<String> post(String json) throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + port + "/timeseries/data"))
                        .header("Authorization", "Bearer " + FAKE_BEARER)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(json))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
