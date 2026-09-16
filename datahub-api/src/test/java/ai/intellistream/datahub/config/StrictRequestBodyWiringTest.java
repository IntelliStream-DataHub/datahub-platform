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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The strict converter is actually the one the running api reads {@code @RequestBody} with.
 *
 * <p>{@link StrictRequestBodyConfig} reaches the converter list through a {@link
 * org.springframework.web.servlet.config.annotation.WebMvcConfigurer} hook, and Spring Boot
 * configures the same list from its own side. Every other test around this class drives the
 * replacement directly or through a builder assembled by hand, so all of them would keep passing if
 * the hook stopped being called at all — and the only symptom in production would be request bodies
 * quietly going back to ignoring unknown fields, which is the exact behaviour
 * {@code StrictRequestBodyConfig} exists to prevent. Nothing would be logged: the warning inside it
 * fires when it runs and finds no Jackson converter, not when it never runs.
 *
 * <p>So this asserts against the real context, on the converters {@link
 * RequestMappingHandlerAdapter} will actually use, rather than a list built for the test.
 */
// RANDOM_PORT rather than MOCK, and the same mocked beans as its neighbours so the cached context
// is shared: WebSocketConfig's ServerContainer needs a real servlet container to start at all.
@SpringBootTest(
        classes = ApiDatahubApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("ctxtest")
class StrictRequestBodyWiringTest {

    @Autowired
    private RequestMappingHandlerAdapter handlerAdapter;

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

    @Test
    @DisplayName("The api's own JSON converter is the strict one, with no lenient one left beside it")
    void theRunningApiReadsBodiesWithTheStrictConverter() {
        List<HttpMessageConverter<?>> converters = handlerAdapter.getMessageConverters();

        assertThat(converters)
                .as("the WebMvcConfigurer hook StrictRequestBodyConfig uses must still be called")
                .anyMatch(StrictJacksonJsonHttpMessageConverter.class::isInstance);

        // Not just "a strict one is present": a plain converter left ahead of it would claim
        // application/json first and read bodies leniently anyway.
        assertThat(converters)
                .as("no lenient Jackson converter may survive the swap")
                .noneMatch(c -> c.getClass() == JacksonJsonHttpMessageConverter.class);
    }
}
