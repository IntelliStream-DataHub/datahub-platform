// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.pulsar;

import ai.intellistream.datahub.api.responses.DataCollectionString;
import ai.intellistream.datahub.api.responses.DataWrapperMessage;
import ai.intellistream.datahub.config.AppInstanceId;
import ai.intellistream.datahub.repositories.subscription.SubscriptionRepository;
import ai.intellistream.datahub.subscription.SubscriptionCache;
import ai.intellistream.datahub.tenant.TenantConfigService;
import org.apache.pulsar.client.admin.PulsarAdmin;
import org.apache.pulsar.client.admin.PulsarAdminException;
import org.apache.pulsar.client.api.Consumer;
import org.apache.pulsar.client.api.Message;
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
import org.testcontainers.containers.PulsarContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Locks down the availability property the per-partition fan-out producer exists for: <b>one
 * fan-out partition the broker refuses producers on must not silence the subscriptions routed to
 * every other partition.</b>
 *
 * <p>In production the refusal came from the fan-out namespace's {@code producer_exception} backlog
 * quota: one abandoned subscription filled its partition past 2 GB, the broker refused producer
 * creation there, and because a partitioned producer is only usable once <em>every</em> partition
 * has connected, the tenant's entire live datapoint feed went silent — with no error surfaced to
 * any connected client. This test reproduces the same broker condition deterministically with a
 * {@code maxProducers} cap rather than by filling a backlog: what matters is that producer creation
 * on exactly one partition fails, not why.
 *
 * <p>The discriminating assertion is that the subscription on a <em>healthy</em> partition still
 * receives its datapoints. That fails against a single partitioned producer — verified against both
 * {@code main} and the async-build-only commit, where the healthy subscription receives nothing.
 *
 * <p>{@code forward()} is also timed, as a standing guard rather than a regression this reproduces:
 * a {@code maxProducers} refusal is terminal, so the create fails fast even on the old synchronous
 * path. The blocking this guards against came from the backlog-quota refusal, which the client
 * retries until its operation timeout; that is modelled directly in
 * {@code SubscriptionFanoutTest.forwardDoesNotBlockWhenAProducerCannotBeCreated} with a create that
 * never completes.
 */
@Tag("integration")
class SubscriptionFanoutPartitionIsolationIT {

    static final String PULSAR_TENANT = "it-isolation";
    static final String NAMESPACE = PULSAR_TENANT + "/subscriptions";
    /**
     * Each test provisions its OWN fan-out topic. The topic-level producer cap one test sets and
     * the partition backlog the other fills are both sticky, and sharing a topic let either leak
     * into the other — a flood could then look "refused" because of the leftover cap rather than
     * the backlog, passing the test for the wrong reason.
     */
    private String fanoutTopic;
    static final int FANOUT_PARTITIONS = 8;

    static final String TENANT_ID = "tenant-isolation-it";
    static final long TIMESERIES_ID = 77L;
    static final String TIMESERIES_EXTERNAL_ID = "ts-isolation";

    /** forward() must not wait on a producer; the pre-fix path blocked for the 30s create timeout. */
    static final Duration FORWARD_BUDGET = Duration.ofSeconds(5);

    /** Tiny, so an abandoned cursor breaches it in a second instead of at production's 2 GB. */
    static final long BACKLOG_LIMIT_BYTES = 16 * 1024;
    /** Padding per flood message, so a few hundred messages clear the quota comfortably. */
    static final int FLOOD_MESSAGE_PADDING = 2048;
    static final int FLOOD_MESSAGES = 400;

    static PulsarContainer pulsar;
    static PulsarClient pulsarClient;
    static PulsarAdmin pulsarAdmin;

    private final List<Consumer<DataWrapperMessage>> consumers = new ArrayList<>();
    private Producer<DataWrapperMessage> slotHog;
    private SubscriptionFanout fanout;

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
                // maxProducers is a topic-level policy; don't rely on the default being on.
                .withEnv("PULSAR_PREFIX_topicLevelPoliciesEnabled", "true")
                // Backlog is accounted per closed ledger and checked on a timer. Production's
                // defaults (10-minute check, 50k-entry ledgers) would make the backlog test
                // take hours.
                .withEnv("PULSAR_PREFIX_backlogQuotaCheckIntervalInSeconds", "1")
                .withEnv("PULSAR_PREFIX_managedLedgerMaxEntriesPerLedger", "20")
                .withEnv("PULSAR_PREFIX_managedLedgerMinLedgerRolloverTimeMinutes", "0")
                .withStartupTimeout(Duration.ofMinutes(3));
    }

    private static void provisionTopics() throws Exception {
        try {
            pulsarAdmin.tenants().createTenant(PULSAR_TENANT, TenantInfo.builder()
                    .allowedClusters(Set.of("standalone")).build());
        } catch (PulsarAdminException.ConflictException ignored) {
            // already there
        }
        try {
            pulsarAdmin.namespaces().createNamespace(NAMESPACE);
        } catch (PulsarAdminException.ConflictException ignored) {
            // already there
        }
    }

    private String createFanoutTopic(String name) throws Exception {
        String topic = "persistent://" + NAMESPACE + "/fanout-" + name;
        pulsarAdmin.topics().createPartitionedTopic(topic, FANOUT_PARTITIONS);
        return topic;
    }

    @AfterEach
    void tearDown() throws Exception {
        if (fanout != null) fanout.close();
        for (Consumer<DataWrapperMessage> consumer : consumers) consumer.close();
        consumers.clear();
        if (slotHog != null) {
            slotHog.close();
            slotHog = null;
        }
    }

    @Test
    @DisplayName("A fanout partition the broker refuses producers on does not silence the other partitions")
    void aRefusedPartitionDoesNotSilenceTheRest() throws Exception {
        fanoutTopic = createFanoutTopic("cap");
        String blocked = "sub-blocked";
        String healthy = externalIdOnAnotherPartition(partitionOf(blocked));
        String blockedPartitionTopic = fanoutTopic + "-partition-" + partitionOf(blocked);

        // One producer allowed per partition, and we take the slot on the blocked one. Set on the
        // parent topic: Pulsar applies a partitioned topic's policies to each of its partitions.
        pulsarAdmin.topicPolicies().setMaxProducers(fanoutTopic, 1);
        // Same schema as the fan-out producer: a raw-bytes producer would pin the topic schema
        // to BYTES and the AVRO consumers below would be refused as incompatible.
        slotHog = pulsarClient.newProducer(Schema.AVRO(DataWrapperMessage.class))
                .topic(blockedPartitionTopic).producerName("slot-hog").create();
        awaitProducersRefusedOn(blockedPartitionTopic);

        List<Message<DataWrapperMessage>> healthyReceived = new CopyOnWriteArrayList<>();
        consumers.add(subscribe(healthy, healthyReceived));
        consumers.add(subscribe(blocked, new CopyOnWriteArrayList<>()));

        SubscriptionCache cache = new SubscriptionCache(
                mock(SubscriptionRepository.class), mock(TenantConfigService.class));
        cache.add(TENANT_ID, TIMESERIES_ID, blocked);
        cache.add(TENANT_ID, TIMESERIES_ID, healthy);

        fanout = new SubscriptionFanout(pulsarClient, topicNames(), cache, new AppInstanceId("isolation-it"));

        long startNanos = System.nanoTime();
        fanout.forward(datapointBatch());
        Duration forwardTook = Duration.ofNanos(System.nanoTime() - startNanos);

        // Asserted before the await so a failure names which fix is missing: a slow forward() is
        // the synchronous build, a silent healthy subscription is the single partitioned producer.
        assertThat(forwardTook)
                .as("forward() runs on the ingest threads after the ClickHouse write; it must not "
                        + "wait out a producer that cannot be created")
                .isLessThan(FORWARD_BUDGET);

        // The whole point: the blocked partition costs only its own subscription.
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(200))
                .until(() -> !healthyReceived.isEmpty());
    }

    /**
     * Topic policies reach the broker asynchronously over {@code __change_events}, so poll until a
     * second producer is actually refused rather than assuming the cap is live.
     */
    private static void awaitProducersRefusedOn(String partitionTopic) {
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(250)).until(() -> {
            try (Producer<DataWrapperMessage> probe = pulsarClient
                    .newProducer(Schema.AVRO(DataWrapperMessage.class))
                    .topic(partitionTopic).producerName("cap-probe").create()) {
                return false;   // still allowed — the cap has not propagated yet
            } catch (Exception expected) {
                return true;
            }
        });
    }

    @Test
    @DisplayName("A subscription flooded and never read does not stop everyone else receiving datapoints")
    void anAbandonedSubscriptionDoesNotSilenceTheRest() throws Exception {
        fanoutTopic = createFanoutTopic("backlog");
        // The namespace policy the fan-out shipped with, and the one this incident turned on.
        pulsarAdmin.namespaces().setBacklogQuota(NAMESPACE, BacklogQuota.builder()
                .limitSize(BACKLOG_LIMIT_BYTES)
                .retentionPolicy(BacklogQuota.RetentionPolicy.producer_exception)
                .build());

        String abandoned = "sub-abandoned";
        String healthy = externalIdOnAnotherPartition(partitionOf(abandoned));
        String abandonedPartitionTopic = fanoutTopic + "-partition-" + partitionOf(abandoned);

        // A durable cursor with nobody on the other end — a client that subscribed and went away.
        // Opened and closed, exactly as a disconnected WebSocket leaves it.
        subscribe(abandoned, new CopyOnWriteArrayList<>()).close();

        floodUntilProducersAreRefused(abandonedPartitionTopic, abandoned);

        // Now the healthy subscriber, on a different partition, connects and expects its datapoints.
        List<Message<DataWrapperMessage>> healthyReceived = new CopyOnWriteArrayList<>();
        consumers.add(subscribe(healthy, healthyReceived));

        SubscriptionCache cache = new SubscriptionCache(
                mock(SubscriptionRepository.class), mock(TenantConfigService.class));
        cache.add(TENANT_ID, TIMESERIES_ID, abandoned);
        cache.add(TENANT_ID, TIMESERIES_ID, healthy);

        fanout = new SubscriptionFanout(pulsarClient, topicNames(), cache, new AppInstanceId("backlog-it"));
        fanout.forward(datapointBatch(TIMESERIES_EXTERNAL_ID));

        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(200))
                .until(() -> !healthyReceived.isEmpty());

        assertThat(healthyReceived)
                .as("the healthy subscription's datapoints must survive another subscription's backlog")
                .isNotEmpty();
    }

    /**
     * Fill the abandoned cursor's partition until the broker starts refusing producers on it —
     * which is what {@code producer_exception} does once the backlog quota is breached. Publishes
     * in rounds and re-checks, because the backlog is only accounted once ledgers close and the
     * quota is evaluated on a timer.
     */
    private static void floodUntilProducersAreRefused(String partitionTopic, String key) throws Exception {
        String padding = "x".repeat(FLOOD_MESSAGE_PADDING);
        for (int round = 0; round < 10; round++) {
            try (Producer<DataWrapperMessage> flooder = pulsarClient
                    .newProducer(Schema.AVRO(DataWrapperMessage.class))
                    .topic(partitionTopic)
                    .producerName("flooder-" + round)
                    .create()) {
                for (int i = 0; i < FLOOD_MESSAGES; i++) {
                    flooder.newMessage().key(key).value(datapointBatch(padding)).send();
                }
            } catch (Exception e) {
                if (isBacklogQuotaRefusal(e)) return;   // bit mid-round: exactly the state we want
                throw e;
            }
            if (producersRefusedOn(partitionTopic)) return;
            Thread.sleep(1_000);   // let the quota check run against the closed ledgers
        }
        throw new IllegalStateException(
                "Backlog quota never tripped on " + partitionTopic + "; the broker settings that make "
                        + "this fast (ledger size, quota check interval) may no longer apply.");
    }

    /**
     * Only a backlog-quota refusal counts. Accepting any failure here would let an unrelated error
     * — a name clash, a closed client — stand in for the condition under test, and the test would
     * then pass without the backlog ever having filled.
     */
    private static boolean producersRefusedOn(String partitionTopic) throws Exception {
        try (Producer<DataWrapperMessage> probe = pulsarClient
                .newProducer(Schema.AVRO(DataWrapperMessage.class))
                .topic(partitionTopic).producerName("quota-probe").create()) {
            return false;
        } catch (Exception e) {
            if (isBacklogQuotaRefusal(e)) return true;
            throw e;
        }
    }

    private static boolean isBacklogQuotaRefusal(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof PulsarClientException.ProducerBlockedQuotaExceededException) return true;
            String message = t.getMessage();
            if (message != null && message.contains("backlog quota exceeded")) return true;
        }
        return false;
    }

    /** Mirrors SubscriptionWebSocketHandler: subscription name = externalId, filter.key pinned. */
    private Consumer<DataWrapperMessage> subscribe(String externalId, List<Message<DataWrapperMessage>> sink)
            throws Exception {
        return pulsarClient.newConsumer(Schema.AVRO(DataWrapperMessage.class))
                .topic(fanoutTopic)
                .subscriptionName(externalId)
                .subscriptionType(SubscriptionType.Failover)
                .consumerName("isolation-it-" + externalId)
                .subscriptionProperties(Map.of(TopicNames.SUBSCRIPTION_FILTER_KEY_PROP, externalId))
                .messageListener((consumer, msg) -> {
                    sink.add(msg);
                    consumer.acknowledgeAsync(msg);
                })
                .subscribe();
    }

    private static DataWrapperMessage datapointBatch() {
        return datapointBatch(TIMESERIES_EXTERNAL_ID);
    }

    private static DataWrapperMessage datapointBatch(String externalId) {
        DataCollectionString item = new DataCollectionString();
        item.setId(TIMESERIES_ID);
        item.setExternalId(externalId);
        item.setValueType("float");
        return new DataWrapperMessage(EventObject.DATAPOINTS, EventAction.CREATE, List.of(item), TENANT_ID);
    }

    private TopicNames topicNames() {
        TopicNames topicNames = mock(TopicNames.class);
        when(topicNames.getSubscriptionFanoutTopicName(anyString())).thenReturn(fanoutTopic);
        return topicNames;
    }

    /**
     * Pulsar's SinglePartitionMessageRouter formula for a keyed message under JavaStringHash,
     * spelled out here rather than called on SubscriptionFanout so this test compiles — and fails —
     * against the versions that predate the per-partition router.
     */
    private static int partitionOf(String externalId) {
        return (externalId.hashCode() & Integer.MAX_VALUE) % FANOUT_PARTITIONS;
    }

    private static String externalIdOnAnotherPartition(int partition) {
        return IntStream.range(0, 1000)
                .mapToObj(i -> "sub-healthy-" + i)
                .filter(id -> partitionOf(id) != partition)
                .findFirst()
                .orElseThrow();
    }
}
