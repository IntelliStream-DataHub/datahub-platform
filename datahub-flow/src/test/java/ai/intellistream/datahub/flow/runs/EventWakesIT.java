// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.flow.runs;

import ai.intellistream.datahub.flow.api.EventMatch;
import ai.intellistream.datahub.flow.store.RunStore;
import ai.intellistream.datahub.flow.store.SharedKvrocks;
import ai.intellistream.datahub.flow.store.TenantKvrocks;
import ai.intellistream.datahub.models.EventModel;
import ai.intellistream.datahub.models.me.MeDataSets;
import ai.intellistream.datahub.pulsar.EventAction;
import ai.intellistream.datahub.pulsar.EventCudMessage;
import ai.intellistream.datahub.pulsar.EventObject;
import ai.intellistream.datahub.pulsar.TopicNames;
import ai.intellistream.datahub.tenant.TenantConfigService;
import ai.intellistream.datahub.tenant.TenantContext;
import org.apache.pulsar.client.admin.PulsarAdmin;
import org.apache.pulsar.client.api.CompressionType;
import org.apache.pulsar.client.api.Producer;
import org.apache.pulsar.client.api.PulsarClient;
import org.apache.pulsar.client.api.Schema;
import org.apache.pulsar.common.policies.data.TenantInfo;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.containers.PulsarContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Event-triggered flows woken off a real broker into a real KVRocks: the events topic as datahub-api
 * provisions it (partitioned) and publishes to it (its producer's schema, keyed by tenant), read by
 * the flow service's own decoding. What the unit test of {@link EventWakes} cannot show: that the
 * subscription works on that topic and that the messages decode.
 */
@Tag("integration")
class EventWakesIT {

    private static final String TENANT = "t";
    private static final String INTERNAL = "internal";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final long ALARMS = 1;
    private static final long MAINTENANCE = 2;

    private static PulsarContainer pulsar;
    private static PulsarClient client;
    private static Producer<EventCudMessage> producer;
    private static TopicNames topics;

    private static final long READABLE = 7;
    private static final long HIDDEN = 8;

    private TenantKvrocks kvrocks;
    private RunStore store;
    private EventWakes wakes;
    /** What the catch-up finds for each flow, as datahub-api would answer the flow's identity. */
    private final Map<Long, Instant> missed = new ConcurrentHashMap<>();
    private final AtomicInteger askedAboutMissed = new AtomicInteger();

    @BeforeAll
    static void start() throws Exception {
        String version = System.getProperty("datahub.pulsar.image.version", "5.0.0");
        pulsar = new PulsarContainer(DockerImageName.parse("apachepulsar/pulsar:" + version))
                .withStartupTimeout(Duration.ofMinutes(3));
        pulsar.start();
        topics = new TopicNames(mock(TenantConfigService.class));
        ReflectionTestUtils.setField(topics, "internalTenant", INTERNAL);
        try (PulsarAdmin admin = PulsarAdmin.builder().serviceHttpUrl(pulsar.getHttpServiceUrl()).build()) {
            admin.tenants().createTenant(INTERNAL, TenantInfo.builder().allowedClusters(Set.of("standalone")).build());
            admin.namespaces().createNamespace(INTERNAL + "/events");
            admin.topics().createPartitionedTopic(topics.getEventsTopicName(), 16);
        }
        client = PulsarClient.builder().serviceUrl(pulsar.getPulsarBrokerUrl()).build();
        // As PulsarProducerConfig makes datahub-api's.
        producer = client.newProducer(Schema.AVRO(EventCudMessage.class))
                .topic(topics.getEventsTopicName())
                .compressionType(CompressionType.ZSTD)
                .create();
    }

    @AfterAll
    static void stop() throws Exception {
        if (producer != null) producer.close();
        if (client != null) client.close();
        if (pulsar != null) pulsar.stop();
    }

    @BeforeEach
    void setUp() {
        SharedKvrocks.flush();
        kvrocks = new TenantKvrocks(SharedKvrocks.tenants(TENANT));
        store = new RunStore(kvrocks, Duration.ofMinutes(1));
        TenantContext.setTenantId(TENANT);
        Instant deployed = store.now().minusSeconds(3600);
        schedule(ALARMS, deployed, new EventMatch(null, List.of("alarm"), null, null, null, null, List.of("agent:*")));
        schedule(MAINTENANCE, deployed, new EventMatch(List.of("pump-7/*"), List.of("maintenance"), null, null, null,
                null, null));
        RunCoordinator coordinator = mock(RunCoordinator.class);
        when(coordinator.flowTenants()).thenReturn(List.of(TENANT));
        EventWakes.FlowIdentity identity = new EventWakes.FlowIdentity() {
            @Override
            public MeDataSets readable(String tenantId, long flowId) {
                return new MeDataSets(false, List.of(READABLE));
            }

            @Override
            public Optional<Instant> recorded(String tenantId, long flowId, EventMatch match, Instant from, Instant to) {
                askedAboutMissed.incrementAndGet();
                return Optional.ofNullable(missed.get(flowId));
            }
        };
        wakes = new EventWakes(client, topics, store, coordinator, identity, JSON, true, Duration.ofMinutes(5));
    }

    @AfterEach
    void tearDown() {
        wakes.stop();
        TenantContext.clear();
        kvrocks.close();
    }

    @Test
    void startingOnALiveTopicWakesOnlyTheFlowsThatMissedAnEvent() {
        Instant missedAt = store.now().minusSeconds(60).truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        missed.put(ALARMS, missedAt);
        wakes.refreshAll();

        await().atMost(Duration.ofSeconds(15)).until(() -> askedAboutMissed.get() == 2);
        await().atMost(Duration.ofSeconds(15)).until(() -> next(ALARMS) != null);
        assertThat(seen(ALARMS)).isEqualTo(missedAt);
        assertThat(next(MAINTENANCE)).as("nothing missed: not run").isNull();
    }

    @Test
    void anEventPublishedAsTheApiDoesWakesTheFlowsItMatchesAndNoOthers() throws Exception {
        started();
        Instant at = store.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);

        // Each later than the event that should wake: had one woken the flow, the flow would have noted
        // its time. What an agent wrote, an alarm in a dataset the flow may not read, a message of
        // another tenant, and a change to an event.
        publish(TENANT, EventAction.CREATE, event("alarm", "x", "agent:watch", at.plusSeconds(5)));
        publish(TENANT, EventAction.CREATE, event("alarm", "x", "plc", at.plusSeconds(5), HIDDEN));
        publish("other", EventAction.CREATE, event("alarm", "x", "plc", at.plusSeconds(5)));
        publish(TENANT, EventAction.UPDATE, event("alarm", "x", "plc", at.plusSeconds(5)));
        publish(TENANT, EventAction.CREATE, event("alarm", "x", "plc", at),
                event("maintenance", "pump-9/oil", "sap", at));

        await().atMost(Duration.ofSeconds(15)).until(() -> next(ALARMS) != null);
        assertThat(seen(ALARMS)).as("woken by the readable plc alarm alone").isEqualTo(at);
        assertThat(next(MAINTENANCE)).as("pump-9 is not among its external ids").isNull();

        publish(TENANT, EventAction.CREATE, event("maintenance", "pump-7/oil", "sap", at));
        await().atMost(Duration.ofSeconds(15)).until(() -> next(MAINTENANCE) != null);
        assertThat(seen(MAINTENANCE)).isEqualTo(at);
    }

    /** Subscribed, with each flow asked about what it missed, which was nothing. */
    private void started() {
        wakes.refreshAll();
        await().atMost(Duration.ofSeconds(15)).until(() -> askedAboutMissed.get() == 2);
    }

    private void schedule(long flowId, Instant deployed, EventMatch match) {
        Schedule schedule = new Schedule(Schedule.EVENTS, null, null, null, Duration.ofSeconds(10),
                Duration.ofSeconds(10), match);
        store.syncSchedule(new RunStore.ScheduleSpec(flowId, "flow-" + flowId, 7, 1, 2, Duration.ofMinutes(15),
                "user-1", schedule.toJson(JSON), schedule.delay(), deployed, null, null, schedule.debounce()));
    }

    /** Asked from Awaitility's thread too, which has no tenant of its own. */
    private Instant next(long flowId) {
        return TenantContext.callWith(TENANT, () -> store.schedule(flowId).orElseThrow().nextTick());
    }

    private static Instant seen(long flowId) {
        String ms = SharedKvrocks.raw().hget("flow:sched:" + flowId, "seen_ms");
        return ms == null ? null : Instant.ofEpochMilli(Long.parseLong(ms));
    }

    private static void publish(String tenantId, EventAction action, EventModel... events) throws Exception {
        EventCudMessage message = new EventCudMessage();
        message.setTenantId(tenantId);
        message.setEventAction(action);
        message.setEventObject(EventObject.EVENT);
        message.setEvents(List.of(events));
        producer.newMessage().key(tenantId).value(message).send();
    }

    private static EventModel event(String type, String externalId, String source, Instant recorded) {
        return event(type, externalId, source, recorded, READABLE);
    }

    private static EventModel event(String type, String externalId, String source, Instant recorded, long dataSetId) {
        EventModel e = new EventModel();
        e.setDataSetId(dataSetId);
        e.setType(type);
        e.setExternalId(externalId);
        e.setSource(source);
        e.setMetadata(Map.of("site", "north"));
        e.setEventTime(recorded.toEpochMilli());
        e.setCreatedTime(recorded.atZone(ZoneOffset.UTC));
        return e;
    }
}
