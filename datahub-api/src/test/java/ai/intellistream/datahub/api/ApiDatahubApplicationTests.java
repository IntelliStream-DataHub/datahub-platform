// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api;

import ai.intellistream.datahub.api.init.pulsar.SubscriptionTopicProvisioner;
import ai.intellistream.datahub.clickhouse.ClickHouseClientPool;
import ai.intellistream.datahub.config.InstanceLock;
import io.github.jopenlibs.vault.Vault;
import org.apache.pulsar.client.admin.PulsarAdmin;
import org.apache.pulsar.client.api.Producer;
import org.apache.pulsar.client.api.PulsarClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Hermetic context-load smoke test.
 *
 * <p>Boots the full application context to verify the bean graph wires and every {@code @Value}
 * placeholder resolves — the static-wiring class of start-up failure. It is deliberately
 * self-contained: the beans that open external connections at start-up are replaced with Mockito
 * beans, and the {@code ctxtest} profile supplies dummy properties, so the test needs no Vault,
 * Keycloak, Pulsar, ClickHouse, Postgres, Neo4j or Valkey and runs deterministically anywhere.
 *
 * <p>What it does NOT cover: real connectivity or the live start-up I/O of the mocked beans
 * (Pulsar topic provisioning, ClickHouse pool warm-up, OIDC discovery, Vault tenant load). That
 * is the job of an environment-gated integration test, not this unit smoke test.
 *
 * <p>Beans mocked, and why each would otherwise hit the network during start-up:
 * <ul>
 *   <li>{@link JwtDecoder} — SecurityConfig's real decoder performs live OIDC discovery against
 *       the issuer.</li>
 *   <li>{@link PulsarClient} / {@link PulsarAdmin} — Pulsar client/admin builders.</li>
 *   <li>The five {@code Producer} beans — each calls {@code create()}, which connects to the
 *       broker and provisions its topic.</li>
 *   <li>{@link ClickHouseClientPool} — connects to ClickHouse on an application event.</li>
 *   <li>{@link SubscriptionTopicProvisioner} — provisions Pulsar topics on start-up.</li>
 * </ul>
 * ({@code TenantFlywayMigrator} and the dev-only {@code InitNamespaces} are switched off via the
 * {@code ctxtest} profile rather than mocked. {@code TenantConfigService} is left real: its
 * Vault load fails fast against the dummy address and is swallowed, leaving an empty tenant map,
 * which other config beans — e.g. {@code FilesConfig} — read directly.)
 */
// RANDOM_PORT starts a real embedded servlet container — WebSocketConfig's servletServerContainer
// bean needs the container's jakarta.websocket ServerContainer, which the default MOCK environment
// does not provide.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("ctxtest")
class ApiDatahubApplicationTests {

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @MockitoBean
    private Vault vault;

    @MockitoBean
    private PulsarClient pulsarClient;

    @MockitoBean
    private PulsarAdmin pulsarAdmin;

    @MockitoBean
    private ClickHouseClientPool clickHouseClientPool;

    @MockitoBean
    private SubscriptionTopicProvisioner subscriptionTopicProvisioner;

    // InstanceLock acquires a Pulsar producer lock in @PostConstruct; with the mocked PulsarClient
    // that would NPE, so replace it here (the five Producer beans are mocked for the same reason).
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
    void contextLoads() {
    }

}
