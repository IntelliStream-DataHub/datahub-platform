// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.websocket;

import ai.intellistream.datahub.api.datasecurity.DatasetGrants;
import ai.intellistream.datahub.api.datasecurity.DatasetPermissions;
import ai.intellistream.datahub.api.datasecurity.DatasetPermissionsResolver;
import ai.intellistream.datahub.api.responses.DataCollectionString;
import ai.intellistream.datahub.api.responses.DataWrapperBin;
import ai.intellistream.datahub.api.responses.DataWrapperMessage;
import ai.intellistream.datahub.api.responses.DatapointString;
import ai.intellistream.datahub.clickhouse.DatapointBinaryConverter;
import ai.intellistream.datahub.pulsar.EventAction;
import ai.intellistream.datahub.pulsar.EventObject;
import ai.intellistream.datahub.pulsar.TopicNames;
import ai.intellistream.datahub.tenant.PulsarTenant;
import ai.intellistream.datahub.tenant.Tenant;
import ai.intellistream.datahub.tenant.TenantConfigService;
import org.apache.pulsar.client.admin.PulsarAdmin;
import org.apache.pulsar.client.admin.PulsarAdminException;
import org.apache.pulsar.client.api.Producer;
import org.apache.pulsar.client.api.PulsarClient;
import org.apache.pulsar.client.api.Schema;
import org.apache.pulsar.common.policies.data.TenantInfo;
import org.junit.jupiter.api.Tag;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;
import org.testcontainers.containers.PulsarContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.Principal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Shared Testcontainers harness for the datapoint-streaming WebSocket integration tests.
 *
 * <p>Spins up a single Apache Pulsar standalone broker for the whole suite, with the
 * {@code datahub-pulsar-filter} broker-side {@code EntryFilter} NAR deployed and enabled, so the
 * subscription fan-out isolation that the production architecture depends on is exercised for real
 * rather than stubbed. The NAR path and broker image version are injected by the Gradle
 * {@code integrationTest} task (see {@code datahub-api/build.gradle}); running these tests outside
 * that task fails fast with a clear message.
 *
 * <p>The tests drive the real handler classes directly with a mocked {@link WebSocketSession}
 * instead of booting the full application. That keeps them focused on the Pulsar-facing behaviour
 * (subscription granularity, broker-side filtering, durability, in-process tail filtering) without
 * needing Postgres, Vault, an OAuth2 issuer or a servlet container: the handlers read their tenant
 * from the session principal / token, and their only database touch ({@code loadSubscription}) is a
 * mockable repository call whose {@code @Transactional} is a no-op on a directly-constructed bean.
 *
 * <p>The container is a process-wide singleton started from a static initializer and stopped via a
 * JVM shutdown hook. Ryuk is disabled for rootless Podman, so the shutdown hook is what reaps it.
 */
@Tag("integration")
abstract class AbstractPulsarWebSocketIT {

    /** Pulsar tenant that hosts the per-customer subscription fan-out topic (see {@link TopicNames}). */
    static final String FANOUT_PULSAR_TENANT = "it-fanout";
    /** Internal tenant that hosts the global all-datapoints ingestion topic. */
    static final String INTERNAL_TENANT = "it-internal";
    /** Cluster name the Testcontainers standalone broker runs under (PULSAR_PREFIX_clusterName). */
    static final String CLUSTER = "standalone";

    static final String FANOUT_TOPIC = "persistent://" + FANOUT_PULSAR_TENANT + "/subscriptions/fanout";
    static final String ALL_DATAPOINTS_TOPIC = "persistent://" + INTERNAL_TENANT + "/datapoints/all-datapoints";

    static final long TS_EPOCH_MILLIS = 1_700_000_000_000L;

    static final JsonMapper JSON = JsonMapper.builder().build();

    static PulsarContainer pulsar;
    static PulsarClient pulsarClient;
    static PulsarAdmin pulsarAdmin;

    static {
        pulsar = createBroker();
        pulsar.start();
        try {
            pulsarClient = PulsarClient.builder().serviceUrl(pulsar.getPulsarBrokerUrl()).build();
            pulsarAdmin = PulsarAdmin.builder().serviceHttpUrl(pulsar.getHttpServiceUrl()).build();
            provisionTenants();
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                if (pulsarAdmin != null) pulsarAdmin.close();
            } catch (Exception ignored) {
                // best-effort
            }
            try {
                if (pulsarClient != null) pulsarClient.close();
            } catch (Exception ignored) {
                // best-effort
            }
            pulsar.stop();
        }));
    }

    private static PulsarContainer createBroker() {
        String version = System.getProperty("datahub.pulsar.image.version", "4.0.11");
        String narPath = System.getProperty("datahub.pulsar.filter.nar");
        if (narPath == null || !Files.exists(Path.of(narPath))) {
            throw new IllegalStateException(
                    "Broker-side entry-filter NAR not found. Run these tests via "
                            + "`./gradlew :datahub-api:integrationTest` (it builds the NAR and sets "
                            + "-Ddatahub.pulsar.filter.nar). Resolved path: " + narPath);
        }
        // Testcontainers' PulsarContainer command is `apply-config-from-env.py standalone.conf &&
        // bin/pulsar standalone`, so PULSAR_PREFIX_* env vars are written into standalone.conf before
        // the broker boots. We drop the NAR into /pulsar/filters and point the broker at it.
        return new PulsarContainer(DockerImageName.parse("apachepulsar/pulsar:" + version))
                .withCopyFileToContainer(
                        MountableFile.forHostPath(narPath),
                        "/pulsar/filters/datahub-pulsar-filter.nar")
                .withEnv("PULSAR_PREFIX_entryFiltersDirectory", "/pulsar/filters")
                .withEnv("PULSAR_PREFIX_entryFilterNames", "datahub-pulsar-filter")
                .withStartupTimeout(Duration.ofMinutes(3));
    }

    private static void provisionTenants() throws Exception {
        createTenant(FANOUT_PULSAR_TENANT);
        createNamespace(FANOUT_PULSAR_TENANT + "/subscriptions");
        createNonPartitionedTopic(FANOUT_TOPIC);

        createTenant(INTERNAL_TENANT);
        createNamespace(INTERNAL_TENANT + "/datapoints");
        createNonPartitionedTopic(ALL_DATAPOINTS_TOPIC);
    }

    private static void createTenant(String tenant) throws Exception {
        try {
            pulsarAdmin.tenants().createTenant(tenant,
                    TenantInfo.builder().allowedClusters(Set.of(CLUSTER)).build());
        } catch (PulsarAdminException.ConflictException alreadyExists) {
            // idempotent provisioning
        }
    }

    private static void createNamespace(String namespace) throws Exception {
        try {
            pulsarAdmin.namespaces().createNamespace(namespace);
        } catch (PulsarAdminException.ConflictException alreadyExists) {
            // idempotent provisioning
        }
    }

    private static void createNonPartitionedTopic(String topic) throws Exception {
        try {
            pulsarAdmin.topics().createNonPartitionedTopic(topic);
        } catch (PulsarAdminException.ConflictException alreadyExists) {
            // idempotent provisioning
        }
    }

    // ---- collaborators wired into the handlers under test --------------------------------------

    /**
     * Build a real {@link TopicNames} backed by a mocked {@link TenantConfigService} so every
     * datahub tenant resolves to {@link #FANOUT_PULSAR_TENANT}. The internal tenant (all-datapoints
     * topic) is set directly since it is a plain {@code @Value} field.
     */
    static TopicNames topicNames() {
        Tenant tenant = new Tenant();
        PulsarTenant pulsarTenant = new PulsarTenant();
        pulsarTenant.setTenant(FANOUT_PULSAR_TENANT);
        tenant.setPulsarTenant(pulsarTenant);

        TenantConfigService configService = mock(TenantConfigService.class);
        lenient().when(configService.getConfig(anyString())).thenReturn(tenant);

        TopicNames topicNames = new TopicNames(configService);
        ReflectionTestUtils.setField(topicNames, "internalTenant", INTERNAL_TENANT);
        return topicNames;
    }

    /**
     * Claim the stubbed resolver ({@link #testGroupsResolver()}) reads the caller's organization
     * group paths from. In production the groups come from the UserInfo endpoint, never from the
     * token; this claim is purely test plumbing so each test token can state its own grants.
     */
    static final String TEST_GROUPS_CLAIM = "test_organization_groups";

    /**
     * A JWT carrying the {@code organization.<x>.id = tenantId} claim the handlers read, the
     * baseline {@code DATAHUB_ACCESS} role, and the all-datasets read grant
     * ({@code /datasets/*&#47;read}). Read-all lets the stream ACL short-circuit so these
     * Pulsar-focused tests need no Postgres dataset fixtures.
     */
    static Jwt jwt(String tenantId) {
        return jwtWithGrants(tenantId, List.of("DATAHUB_ACCESS"), List.of("/datasets/*/read"));
    }

    /** A JWT with an explicit {@code realm_access.roles} list and no dataset grants. */
    static Jwt jwtWithRealmRoles(String tenantId, List<String> realmRoles) {
        return jwtWithGrants(tenantId, realmRoles, List.of());
    }

    /** A JWT with explicit realm roles (raw names, no {@code ROLE_} prefix) and group grants. */
    static Jwt jwtWithGrants(String tenantId, List<String> realmRoles, List<String> groupPaths) {
        return Jwt.withTokenValue("test-token-" + tenantId)
                .header("alg", "none")
                .claim("organization", Map.of("org", Map.of("id", tenantId)))
                .claim("realm_access", Map.of("roles", realmRoles))
                .claim(TEST_GROUPS_CLAIM, groupPaths)
                .subject("integration-test")
                .build();
    }

    static JwtAuthenticationToken principal(String tenantId) {
        return new JwtAuthenticationToken(jwt(tenantId), accessAuthorities());
    }

    /** A handshake principal that may use the API but holds no dataset grants at all. */
    static JwtAuthenticationToken principalWithoutDatasetGrants(String tenantId) {
        return new JwtAuthenticationToken(
                jwtWithRealmRoles(tenantId, List.of("DATAHUB_ACCESS")), accessAuthorities());
    }

    private static List<GrantedAuthority> accessAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_DATAHUB_ACCESS"));
    }

    /**
     * A resolver deriving permissions from the token's {@link #TEST_GROUPS_CLAIM} through the
     * production group-path grammar, with no UserInfo or closure lookup. Wildcard grants become
     * blanket flags exactly as in production; per-dataset paths would need the closure machinery
     * and resolve to nothing here, which keeps these ITs about Pulsar fan-out rather than the ACL.
     */
    static DatasetPermissionsResolver testGroupsResolver() {
        DatasetPermissionsResolver resolver = mock(DatasetPermissionsResolver.class);
        when(resolver.forJwt(any())).thenAnswer(inv ->
                permissionsFromTestClaim(inv.getArgument(0, Jwt.class)));
        when(resolver.forPrincipal(any())).thenAnswer(inv -> {
            Object p = inv.getArgument(0);
            return p instanceof JwtAuthenticationToken jwtAuth
                    ? permissionsFromTestClaim(jwtAuth.getToken())
                    : DatasetPermissions.none();
        });
        return resolver;
    }

    private static DatasetPermissions permissionsFromTestClaim(Jwt jwt) {
        if (jwt == null) {
            return DatasetPermissions.none();
        }
        DatasetGrants grants = DatasetGrants.from(jwt.getClaimAsStringList(TEST_GROUPS_CLAIM));
        return DatasetPermissions.of(grants.readAll(), grants.writeAll(), Set.of(), Set.of());
    }

    /**
     * A mock {@link WebSocketSession} that records every {@link TextMessage} the handler sends.
     * PING frames and other non-text messages are ignored.
     */
    static WebSocketSession mockSession(String id, URI uri, Principal principal, List<TextMessage> outbox) {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn(id);
        when(session.isOpen()).thenReturn(true);
        when(session.getUri()).thenReturn(uri);
        if (principal != null) {
            when(session.getPrincipal()).thenReturn(principal);
        }
        try {
            doAnswer(invocation -> {
                WebSocketMessage<?> message = invocation.getArgument(0);
                if (message instanceof TextMessage text) {
                    synchronized (outbox) {
                        outbox.add(text);
                    }
                }
                return null;
            }).when(session).sendMessage(any());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return session;
    }

    // ---- message builders / producers ----------------------------------------------------------

    static DataCollectionString item(String timeseriesExternalId, String valueType, String value) {
        DataCollectionString collection = new DataCollectionString();
        collection.setExternalId(timeseriesExternalId);
        collection.setValueType(valueType);
        collection.setDatapoints(List.of(new DatapointString(String.valueOf(TS_EPOCH_MILLIS), value)));
        return collection;
    }

    /** Fan-out payloads are opaque to the subscription handler, so a numeric BIGINT value is fine. */
    static DataWrapperMessage datapointMessage(String tenantId, String timeseriesExternalId, String value) {
        return new DataWrapperMessage(EventObject.DATAPOINTS, EventAction.CREATE,
                List.of(item(timeseriesExternalId, "BIGINT", value)), tenantId);
    }

    /** Publish to a subscription fan-out topic keyed by the subscription externalId, batching off. */
    static void produceFanout(String topic, String subscriptionExternalId, DataWrapperMessage message) throws Exception {
        try (Producer<DataWrapperMessage> producer = pulsarClient
                .newProducer(Schema.AVRO(DataWrapperMessage.class))
                .topic(topic)
                .enableBatching(false)
                .create()) {
            producer.newMessage().key(subscriptionExternalId).value(message).send();
        }
    }

    /**
     * Publish a binary datapoint envelope to the all-datapoints topic (the live-tail source). Uses a
     * TEXT value type so tests can assert on readable string labels (and exercises the binary TEXT
     * round-trip the tail handler performs).
     */
    static void produceAllDatapoints(String tenantId, EventAction action, String timeseriesExternalId, String value)
            throws Exception {
        DataWrapperMessage message = new DataWrapperMessage(EventObject.DATAPOINTS, action,
                List.of(item(timeseriesExternalId, "TEXT", value)), tenantId);
        DataWrapperBin bin = DatapointBinaryConverter.toBinary(message);
        try (Producer<DataWrapperBin> producer = pulsarClient
                .newProducer(Schema.AVRO(DataWrapperBin.class))
                .topic(ALL_DATAPOINTS_TOPIC)
                .enableBatching(false)
                .create()) {
            producer.newMessage().value(bin).send();
        }
    }

    // ---- frame parsing helpers -----------------------------------------------------------------

    static JsonNode parse(TextMessage frame) {
        return JSON.readTree(frame.getPayload());
    }

    static List<TextMessage> snapshot(List<TextMessage> outbox) {
        synchronized (outbox) {
            return List.copyOf(outbox);
        }
    }
}
