// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.pulsar;

import ai.intellistream.datahub.api.responses.DataCollectionString;
import ai.intellistream.datahub.api.responses.DataWrapperBin;
import ai.intellistream.datahub.api.responses.DataWrapperMessage;
import ai.intellistream.datahub.api.responses.DatapointString;
import ai.intellistream.datahub.clickhouse.ClickHouseDatapointService;
import ai.intellistream.datahub.clickhouse.DatapointBinaryConverter;
import ai.intellistream.datahub.repositories.subscription.SubscriptionRepository;
import ai.intellistream.datahub.subscription.SubscriptionCache;
import ai.intellistream.datahub.tenant.PulsarTenant;
import ai.intellistream.datahub.tenant.Tenant;
import ai.intellistream.datahub.tenant.TenantConfigService;
import org.apache.pulsar.client.admin.PulsarAdmin;
import org.apache.pulsar.client.admin.PulsarAdminException;
import org.apache.pulsar.client.api.Consumer;
import org.apache.pulsar.client.api.Message;
import org.apache.pulsar.client.api.Producer;
import org.apache.pulsar.client.api.PulsarClient;
import org.apache.pulsar.client.api.Schema;
import org.apache.pulsar.client.api.SubscriptionType;
import org.apache.pulsar.common.policies.data.TenantInfo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.containers.PulsarContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Regression test for the shared-timeseries fan-out bug: {@link BatchedDatapointsListener}'s
 * fanout producer uses KEY_BASED batching, which groups batches by <b>orderingKey</b> when one is
 * set. If the orderingKey diverges from the partition key (as it did when it was the timeseriesId),
 * messages destined for <i>different</i> subscriptions of the same timeseries collapse into one
 * batch carrying a single partition key, and the broker-side {@code SubscriptionKeyEntryFilter}
 * delivers the whole batch to one subscription (duplicates) while silently dropping it for all the
 * others. The invariant under test: <b>the key the batcher groups by must equal the key the filter
 * routes by</b>.
 *
 * <p>This test drives the REAL listener (and therefore the real producer configuration) end to end
 * against a Pulsar broker with the {@code datahub-pulsar-filter} NAR loaded: publish one datapoint
 * envelope to the all-datapoints topic, let the listener fan it out, and assert every subscription
 * bound to the timeseries receives it exactly once.
 *
 * <p>Production's fanout topic is partitioned (8 partitions, see {@code
 * SubscriptionTopicProvisioner}) and messages route by {@code JavaStringHash(key) % partitions},
 * so subscriptions only ever share a batch container when their externalIds hash to the same
 * partition — the bug looks intermittent per subscription pair. The test reproduces the collision
 * deterministically by picking externalIds that all hash to one partition; with randomly chosen
 * ids it could pass by luck on broken code.
 *
 * <p>Container/NAR wiring mirrors {@code AbstractPulsarWebSocketIT} in datahub-api: run via
 * {@code ./gradlew :datahub-stateless-consumer:integrationTest}, which builds the NAR and sets the
 * system properties; Ryuk is disabled for rootless Podman, so a JVM shutdown hook reaps the
 * container.
 */
@Tag("integration")
class BatchedDatapointsFanoutIT {

    static final String FANOUT_PULSAR_TENANT = "it-fanout";
    static final String INTERNAL_TENANT = "it-internal";
    static final String CLUSTER = "standalone";

    static final String FANOUT_TOPIC = "persistent://" + FANOUT_PULSAR_TENANT + "/subscriptions/fanout";
    static final String ALL_DATAPOINTS_TOPIC = "persistent://" + INTERNAL_TENANT + "/datapoints/all-datapoints";

    /** Same partition count as production (SubscriptionTopicProvisioner.SUBSCRIPTION_FANOUT_PARTITIONS). */
    static final int FANOUT_PARTITIONS = 8;
    /** ≥2 triggers the bug; a few more make the co-batching repro robust against batch-window splits. */
    static final int COLLIDING_SUBSCRIPTIONS = 5;

    static final String TENANT_ID = "tenant-fanout-it";
    static final long TIMESERIES_ID = 4242L;
    static final String TIMESERIES_EXTERNAL_ID = "ts-shared";
    static final long TS_EPOCH_MILLIS = 1_700_000_000_000L;

    static PulsarContainer pulsar;
    static PulsarClient pulsarClient;
    static PulsarAdmin pulsarAdmin;

    static {
        pulsar = createBroker();
        pulsar.start();
        try {
            pulsarClient = PulsarClient.builder().serviceUrl(pulsar.getPulsarBrokerUrl()).build();
            pulsarAdmin = PulsarAdmin.builder().serviceHttpUrl(pulsar.getHttpServiceUrl()).build();
            provisionTopics();
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
                            + "`./gradlew :datahub-stateless-consumer:integrationTest` (it builds the NAR and sets "
                            + "-Ddatahub.pulsar.filter.nar). Resolved path: " + narPath);
        }
        return new PulsarContainer(DockerImageName.parse("apachepulsar/pulsar:" + version))
                .withCopyFileToContainer(
                        MountableFile.forHostPath(narPath),
                        "/pulsar/filters/datahub-pulsar-filter.nar")
                .withEnv("PULSAR_PREFIX_entryFiltersDirectory", "/pulsar/filters")
                .withEnv("PULSAR_PREFIX_entryFilterNames", "datahub-pulsar-filter")
                .withStartupTimeout(Duration.ofMinutes(3));
    }

    private static void provisionTopics() throws Exception {
        createTenant(FANOUT_PULSAR_TENANT);
        createNamespace(FANOUT_PULSAR_TENANT + "/subscriptions");
        // Partitioned like production: the SinglePartition-with-key router spreads subscriptions
        // across partitions, and only same-partition subscriptions can ever share a batch.
        pulsarAdmin.topics().createPartitionedTopic(FANOUT_TOPIC, FANOUT_PARTITIONS);

        createTenant(INTERNAL_TENANT);
        createNamespace(INTERNAL_TENANT + "/datapoints");
        pulsarAdmin.topics().createNonPartitionedTopic(ALL_DATAPOINTS_TOPIC);
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

    private BatchedDatapointsListener listener;
    private final List<Consumer<DataWrapperMessage>> consumers = new ArrayList<>();

    @AfterEach
    void tearDown() throws Exception {
        if (listener != null) listener.cleanup();
        for (Consumer<DataWrapperMessage> consumer : consumers) {
            consumer.close();
        }
    }

    @Test
    @DisplayName("Every subscription sharing a timeseries receives its datapoints exactly once, even when their keys share a fanout partition")
    void sharedTimeseriesFanOutDeliversToEverySubscriptionExactlyOnce() throws Exception {
        List<String> externalIds = collidingExternalIds();
        Map<String, List<Message<DataWrapperMessage>>> received = new ConcurrentHashMap<>();

        // Bind every subscription to the one timeseries, exactly what SubscriptionCache holds in
        // production after SubscriptionNotifyListener updates.
        SubscriptionCache cache = new SubscriptionCache(
                mock(SubscriptionRepository.class), mock(TenantConfigService.class));
        for (String externalId : externalIds) {
            cache.add(TENANT_ID, TIMESERIES_ID, externalId);
        }

        // One durable consumer per subscription, mirroring SubscriptionWebSocketHandler's config:
        // subscription name = externalId, filter.key pinned so the broker-side entry filter routes.
        for (String externalId : externalIds) {
            received.put(externalId, new CopyOnWriteArrayList<>());
            consumers.add(pulsarClient.newConsumer(Schema.AVRO(DataWrapperMessage.class))
                    .topic(FANOUT_TOPIC)
                    .subscriptionName(externalId)
                    .subscriptionType(SubscriptionType.Failover)
                    .consumerName("fanout-it-" + externalId)
                    .subscriptionProperties(Map.of(TopicNames.SUBSCRIPTION_FILTER_KEY_PROP, externalId))
                    .messageListener((consumer, msg) -> {
                        received.get(externalId).add(msg);
                        consumer.acknowledgeAsync(msg);
                    })
                    .subscribe());
        }

        // The REAL listener: consumes the all-datapoints topic and fans out with the production
        // producer configuration. ClickHouse is mocked out; the Pulsar path is fully real.
        listener = new BatchedDatapointsListener(
                pulsarClient, mock(ClickHouseDatapointService.class), topicNames(), cache,
                new ai.intellistream.datahub.config.AppInstanceId("fanout-it"));
        listener.init();

        produceAllDatapoints(TIMESERIES_ID, TIMESERIES_EXTERNAL_ID, "42");

        // Every subscription must see the datapoint. On broken batching only the batch-key winner
        // is served and the rest starve, so this await times out.
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(200)).until(() ->
                externalIds.stream().allMatch(id -> !received.get(id).isEmpty()));

        // Quiet period to let any stray duplicates (the winner-side symptom) arrive before counting.
        Thread.sleep(2000);

        for (String externalId : externalIds) {
            List<Message<DataWrapperMessage>> messages = received.get(externalId);
            assertThat(messages)
                    .as("subscription %s must receive the shared-timeseries datapoint exactly once", externalId)
                    .hasSize(1);

            Message<DataWrapperMessage> msg = messages.get(0);
            assertThat(msg.getKey())
                    .as("delivered message must be keyed for the receiving subscription")
                    .isEqualTo(externalId);

            DataCollectionString item = msg.getValue().getItems().iterator().next();
            assertThat(item.getExternalId()).isEqualTo(TIMESERIES_EXTERNAL_ID);
            assertThat(item.getDatapoints().iterator().next().getValue()).isEqualTo("42");
        }
    }

    /**
     * Subscription externalIds that all route to the same fanout partition, computed exactly as the
     * producer does: {@code JavaStringHash} ({@code hashCode() & Integer.MAX_VALUE}) mod the
     * partition count.
     */
    private static List<String> collidingExternalIds() {
        Map<Integer, List<String>> byPartition = new HashMap<>();
        for (int i = 0; i < 512; i++) {
            String candidate = "shared-ts-sub-" + i;
            int partition = (candidate.hashCode() & Integer.MAX_VALUE) % FANOUT_PARTITIONS;
            List<String> bucket = byPartition.computeIfAbsent(partition, p -> new ArrayList<>());
            bucket.add(candidate);
            if (bucket.size() == COLLIDING_SUBSCRIPTIONS) {
                return bucket;
            }
        }
        throw new IllegalStateException("No " + COLLIDING_SUBSCRIPTIONS + "-way partition collision in 512 candidates");
    }

    /** Real {@link TopicNames} resolving every datahub tenant to the test's fanout Pulsar tenant. */
    private static TopicNames topicNames() {
        Tenant tenant = new Tenant();
        PulsarTenant pulsarTenant = new PulsarTenant();
        pulsarTenant.setTenant(FANOUT_PULSAR_TENANT);
        tenant.setPulsarTenant(pulsarTenant);

        TenantConfigService configService = mock(TenantConfigService.class);
        when(configService.getConfig(anyString())).thenReturn(tenant);

        TopicNames topicNames = new TopicNames(configService);
        ReflectionTestUtils.setField(topicNames, "internalTenant", INTERNAL_TENANT);
        return topicNames;
    }

    /** Publish a binary CREATE envelope to the all-datapoints topic, as the API's ingest path does. */
    private static void produceAllDatapoints(long timeseriesId, String timeseriesExternalId, String value)
            throws Exception {
        DataCollectionString item = new DataCollectionString();
        item.setId(timeseriesId);
        item.setExternalId(timeseriesExternalId);
        item.setValueType("BIGINT");
        item.setDatapoints(List.of(new DatapointString(String.valueOf(TS_EPOCH_MILLIS), value)));

        DataWrapperBin bin = DatapointBinaryConverter.toBinary(new DataWrapperMessage(
                EventObject.DATAPOINTS, EventAction.CREATE, List.of(item), TENANT_ID));
        try (Producer<DataWrapperBin> producer = pulsarClient
                .newProducer(Schema.AVRO(DataWrapperBin.class))
                .topic(ALL_DATAPOINTS_TOPIC)
                .enableBatching(false)
                .create()) {
            producer.newMessage().value(bin).send();
        }
    }
}
