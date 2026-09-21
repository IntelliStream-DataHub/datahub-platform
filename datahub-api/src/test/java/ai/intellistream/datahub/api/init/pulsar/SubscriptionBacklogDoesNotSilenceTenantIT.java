// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.init.pulsar;

import ai.intellistream.datahub.api.messaging.PartitionedTopicProvisioner;
import ai.intellistream.datahub.api.responses.DataCollectionString;
import ai.intellistream.datahub.api.responses.DataWrapperMessage;
import ai.intellistream.datahub.pulsar.EventAction;
import ai.intellistream.datahub.pulsar.EventObject;
import ai.intellistream.datahub.pulsar.TopicNames;
import ai.intellistream.datahub.tenant.PulsarTenant;
import ai.intellistream.datahub.tenant.Tenant;
import ai.intellistream.datahub.tenant.TenantConfigService;
import org.apache.pulsar.client.admin.PulsarAdmin;
import org.apache.pulsar.client.admin.PulsarAdminException;
import org.apache.pulsar.client.api.Consumer;
import org.apache.pulsar.client.api.HashingScheme;
import org.apache.pulsar.client.api.Message;
import org.apache.pulsar.client.api.MessageRoutingMode;
import org.apache.pulsar.client.api.Producer;
import org.apache.pulsar.client.api.PulsarClient;
import org.apache.pulsar.client.api.PulsarClientException;
import org.apache.pulsar.client.api.Schema;
import org.apache.pulsar.client.api.SubscriptionType;
import org.apache.pulsar.common.policies.data.BacklogQuota;
import org.apache.pulsar.common.policies.data.TenantInfo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.containers.PulsarContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

/**
 * The production incident, at the layer that actually caused it: <b>one subscription that is
 * written to constantly and never read must not take live subscriptions away from the rest of the
 * tenant.</b>
 *
 * <p>A WebSocket subscription is a durable Pulsar cursor on the tenant's fan-out topic, so a client
 * that stops reading leaves its backlog growing forever. What happens next is decided by the
 * namespace's backlog-quota <em>retention policy</em>, and that policy is chosen here, by
 * {@link SubscriptionTopicProvisioner}. Under {@code producer_exception} the broker answers a
 * breached quota by refusing <b>producer creation</b> on that cursor's partition — and since the
 * fan-out publishes through one producer on the partitioned topic, which Pulsar only makes usable
 * once every partition has connected, the whole tenant's live feed went silent. No error reached
 * any connected client: sockets stayed open and delivered nothing.
 *
 * <p>So this test lets the real provisioner create the namespace and topic, floods an abandoned
 * cursor past the quota, and then does exactly what the fan-out does — opens a producer on the
 * partitioned topic and publishes — asserting a healthy subscriber still receives. It fails
 * against the {@code producer_exception} policy this replaced.
 *
 * <p>Only the quota's <em>size</em> is scaled down, to 16 KB, so an abandoned cursor breaches it in
 * seconds rather than at production's 2 GB. The retention policy — the thing under test — is read
 * back from what the provisioner applied and re-used as-is.
 */
@Tag("integration")
class SubscriptionBacklogDoesNotSilenceTenantIT {

    static final String PULSAR_TENANT = "it-eviction";
    static final String NAMESPACE = PULSAR_TENANT + "/subscriptions";
    static final String FANOUT_TOPIC = "persistent://" + NAMESPACE + "/fanout";
    static final String INTERNAL_TENANT = "it-eviction-internal";

    static final String DATAHUB_TENANT_ID = "tenant-eviction-it";
    static final long TIMESERIES_ID = 55L;

    /** Scaled down from production's 2 GB so the flood takes seconds. The policy is not scaled. */
    static final long BACKLOG_LIMIT_BYTES = 16 * 1024;
    static final int FLOOD_MESSAGE_PADDING = 2048;
    static final int FLOOD_MESSAGES = 400;
    static final int FLOOD_ROUNDS = 10;

    static PulsarContainer pulsar;
    static PulsarClient pulsarClient;
    static PulsarAdmin pulsarAdmin;

    private final List<Consumer<DataWrapperMessage>> consumers = new ArrayList<>();

    static {
        pulsar = createBroker();
        pulsar.start();
        try {
            pulsarClient = PulsarClient.builder().serviceUrl(pulsar.getPulsarBrokerUrl()).build();
            pulsarAdmin = PulsarAdmin.builder().serviceHttpUrl(pulsar.getHttpServiceUrl()).build();
            createTenant(PULSAR_TENANT);
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
        return new PulsarContainer(DockerImageName.parse("apachepulsar/pulsar:" + version))
                // Backlog is accounted per closed ledger and checked on a timer. Production's
                // defaults (10-minute check, 50k-entry ledgers) would make this test take hours.
                .withEnv("PULSAR_PREFIX_backlogQuotaCheckIntervalInSeconds", "1")
                .withEnv("PULSAR_PREFIX_managedLedgerMaxEntriesPerLedger", "20")
                .withEnv("PULSAR_PREFIX_managedLedgerMinLedgerRolloverTimeMinutes", "0")
                .withStartupTimeout(Duration.ofMinutes(3));
    }

    private static void createTenant(String tenant) throws Exception {
        try {
            pulsarAdmin.tenants().createTenant(tenant, TenantInfo.builder()
                    .allowedClusters(Set.of("standalone")).build());
        } catch (PulsarAdminException.ConflictException ignored) {
            // already there
        }
    }

    @AfterEach
    void tearDown() throws Exception {
        for (Consumer<DataWrapperMessage> consumer : consumers) consumer.close();
        consumers.clear();
    }

    @Test
    @DisplayName("A subscription flooded and never read does not stop the rest of the tenant receiving datapoints")
    void anAbandonedSubscriptionDoesNotSilenceTheTenant() throws Exception {
        // The real provisioner creates the namespace and the partitioned fan-out topic, and picks
        // the backlog policy. That choice is what this test exercises.
        provisioner().provisionAll();
        shrinkQuotaKeepingTheProvisionedPolicy();

        String abandoned = "sub-abandoned";
        String healthy = "sub-healthy";
        String abandonedPartitionTopic = FANOUT_TOPIC + "-partition-" + partitionOf(abandoned);

        // A durable cursor with nobody on the other end — a client that subscribed and went away.
        subscribe(abandoned, new CopyOnWriteArrayList<>()).close();
        flood(abandonedPartitionTopic, abandoned);

        List<Message<DataWrapperMessage>> healthyReceived = new CopyOnWriteArrayList<>();
        consumers.add(subscribe(healthy, healthyReceived));

        // Exactly what SubscriptionFanout does: one producer on the partitioned topic, keyed by
        // the subscription externalId.
        try (Producer<DataWrapperMessage> fanoutProducer = pulsarClient
                .newProducer(Schema.AVRO(DataWrapperMessage.class))
                .topic(FANOUT_TOPIC)
                .hashingScheme(HashingScheme.JavaStringHash)
                .messageRoutingMode(MessageRoutingMode.SinglePartition)
                .create()) {
            fanoutProducer.newMessage().key(healthy).value(datapointBatch("live")).send();
        } catch (PulsarClientException e) {
            fail("the fan-out producer could not publish after one subscription's backlog filled, "
                    + "so every subscription in the tenant would be silent: " + e.getMessage());
        }

        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(200))
                .until(() -> !healthyReceived.isEmpty());

        assertThat(healthyReceived)
                .as("a healthy subscription's datapoints must survive another subscription's backlog")
                .isNotEmpty();
    }

    /**
     * Keep the retention policy the provisioner chose — that is the behaviour under test — but drop
     * the size limit so an abandoned cursor breaches it in seconds instead of at 2 GB.
     */
    private static void shrinkQuotaKeepingTheProvisionedPolicy() throws Exception {
        BacklogQuota provisioned = pulsarAdmin.namespaces().getBacklogQuotaMap(NAMESPACE)
                .get(BacklogQuota.BacklogQuotaType.destination_storage);
        assertThat(provisioned)
                .as("the provisioner must have applied a destination_storage backlog quota")
                .isNotNull();
        pulsarAdmin.namespaces().setBacklogQuota(NAMESPACE, BacklogQuota.builder()
                .limitSize(BACKLOG_LIMIT_BYTES)
                .retentionPolicy(provisioned.getPolicy())
                .build());
    }

    /**
     * Fill the abandoned cursor's partition well past the quota. Under {@code producer_exception}
     * the broker starts refusing producers part-way through, which is the condition under test, so
     * that refusal is swallowed here rather than failing the test early.
     */
    private static void flood(String partitionTopic, String key) throws Exception {
        String padding = "x".repeat(FLOOD_MESSAGE_PADDING);
        for (int round = 0; round < FLOOD_ROUNDS; round++) {
            try (Producer<DataWrapperMessage> flooder = pulsarClient
                    .newProducer(Schema.AVRO(DataWrapperMessage.class))
                    .topic(partitionTopic)
                    .producerName("flooder-" + round)
                    .create()) {
                for (int i = 0; i < FLOOD_MESSAGES; i++) {
                    flooder.newMessage().key(key).value(datapointBatch(padding)).send();
                }
            } catch (PulsarClientException quotaRefusal) {
                return;
            }
            Thread.sleep(500);   // let the quota check run against the closed ledgers
        }
    }

    private Consumer<DataWrapperMessage> subscribe(String externalId, List<Message<DataWrapperMessage>> sink)
            throws Exception {
        return pulsarClient.newConsumer(Schema.AVRO(DataWrapperMessage.class))
                .topic(FANOUT_TOPIC)
                .subscriptionName(externalId)
                .subscriptionType(SubscriptionType.Failover)
                .consumerName("eviction-it-" + externalId)
                .subscriptionProperties(Map.of(TopicNames.SUBSCRIPTION_FILTER_KEY_PROP, externalId))
                .messageListener((consumer, msg) -> {
                    sink.add(msg);
                    consumer.acknowledgeAsync(msg);
                })
                .subscribe();
    }

    /** The provisioner under test, wired to one datahub tenant on this broker's Pulsar tenant. */
    private static SubscriptionTopicProvisioner provisioner() {
        Tenant tenant = new Tenant();
        tenant.setOrganizationId(DATAHUB_TENANT_ID);
        PulsarTenant pulsarTenant = new PulsarTenant();
        pulsarTenant.setTenant(PULSAR_TENANT);
        tenant.setPulsarTenant(pulsarTenant);

        // Mocked: only cachedTenants and getConfig are reached, and the real service wants Vault.
        TenantConfigService configService = mock(TenantConfigService.class);
        configService.cachedTenants = new ConcurrentHashMap<>(Map.of(DATAHUB_TENANT_ID, tenant));
        lenient().when(configService.getConfig(anyString())).thenReturn(tenant);

        TopicNames topicNames = new TopicNames(configService);
        ReflectionTestUtils.setField(topicNames, "internalTenant", INTERNAL_TENANT);

        return new SubscriptionTopicProvisioner(
                pulsarAdmin, topicNames, configService, new PartitionedTopicProvisioner(pulsarAdmin));
    }

    private static DataWrapperMessage datapointBatch(String externalId) {
        DataCollectionString item = new DataCollectionString();
        item.setId(TIMESERIES_ID);
        item.setExternalId(externalId);
        item.setValueType("float");
        return new DataWrapperMessage(
                EventObject.DATAPOINTS, EventAction.CREATE, List.of(item), DATAHUB_TENANT_ID);
    }

    /** Pulsar's SinglePartitionMessageRouter formula for a keyed message under JavaStringHash. */
    private static int partitionOf(String externalId) {
        return (externalId.hashCode() & Integer.MAX_VALUE) % 8;
    }
}
