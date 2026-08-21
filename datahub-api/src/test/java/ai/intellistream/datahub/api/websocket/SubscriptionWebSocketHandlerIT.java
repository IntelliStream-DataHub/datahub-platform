// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.websocket;

import ai.intellistream.datahub.api.datasecurity.StreamAccessAuthorizer;
import ai.intellistream.datahub.jpa.domains.SubscriptionEntity;
import ai.intellistream.datahub.pulsar.TopicNames;
import ai.intellistream.datahub.repositories.node.TimeseriesRepository;
import ai.intellistream.datahub.repositories.subscription.SubscriptionRepository;
import ai.intellistream.datahub.subscription.SubscriptionType;
import org.apache.pulsar.client.api.MessageId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.JsonNode;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;

/**
 * Integration tests for {@link SubscriptionWebSocketHandler} against a real Pulsar broker with the
 * broker-side {@code SubscriptionKeyEntryFilter} NAR loaded.
 *
 * <p>These verify the documented subscription model:
 * <ul>
 *   <li>one Pulsar subscription per subscription externalId, covering many timeseries;</li>
 *   <li>broker-side {@code filter.key} isolation so a subscription only sees its own messages;</li>
 *   <li>a single socket multiplexing several subscriptions (path + dynamic {@code subscribe});</li>
 *   <li>durable cursors that retain and redeliver backlog across a reconnect.</li>
 * </ul>
 */
class SubscriptionWebSocketHandlerIT extends AbstractPulsarWebSocketIT {

    private SubscriptionWebSocketHandler handler;
    private SubscriptionRepository subscriptionRepository;
    private final Map<Long, SubscriptionEntity> registry = new ConcurrentHashMap<>();

    @BeforeEach
    void setUp() {
        registry.clear();
        subscriptionRepository = mock(SubscriptionRepository.class);
        when(subscriptionRepository.findByExternalIdHash(anyLong()))
                .thenAnswer(invocation -> Optional.ofNullable(registry.get(invocation.<Long>getArgument(0))));
        TopicNames topicNames = topicNames();
        StreamAccessAuthorizer authorizer = new StreamAccessAuthorizer(
                mock(TimeseriesRepository.class), subscriptionRepository, testGroupsResolver());
        handler = new SubscriptionWebSocketHandler(pulsarClient, topicNames, subscriptionRepository, JSON, authorizer);
    }

    @AfterEach
    void tearDown() {
        if (handler != null) handler.shutdown();
    }

    @Test
    @DisplayName("One subscription delivers all of its timeseries and the broker filter hides other subscriptions'")
    void subscriptionReceivesAllItsTimeseriesAndNotOthers() throws Exception {
        registerSubscription("ext-iso-a", SubscriptionType.FAILOVER);
        registerSubscription("ext-iso-b", SubscriptionType.FAILOVER);

        List<TextMessage> outbox = synchronizedList();
        WebSocketSession ws = mockSession("iso-1", listenUri("ext-iso-a"), principal("tenant-iso"), outbox);
        handler.afterConnectionEstablished(ws);

        // Produce the foreign-subscription message FIRST: if the broker filter were not routing on
        // filter.key, it would arrive on ext-iso-a's consumer before the two legitimate ones.
        produceFanout(FANOUT_TOPIC, "ext-iso-b", datapointMessage("tenant-iso", "ts-foreign", "9"));
        produceFanout(FANOUT_TOPIC, "ext-iso-a", datapointMessage("tenant-iso", "ts-1", "1"));
        produceFanout(FANOUT_TOPIC, "ext-iso-a", datapointMessage("tenant-iso", "ts-2", "2"));

        // One subscription covers many timeseries: both ts-1 and ts-2 arrive on the single ext-iso-a stream.
        await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(200)).until(() ->
                timeseriesIdsFor(outbox, "ext-iso-a").containsAll(Set.of("ts-1", "ts-2")));

        // Broker-side filtering: ext-iso-a never receives the ext-iso-b-keyed message, and no frame
        // is ever tagged with a foreign subscription externalId.
        assertThat(timeseriesIdsFor(outbox, "ext-iso-a")).doesNotContain("ts-foreign");
        assertThat(subscriptionExternalIds(outbox)).containsOnly("ext-iso-a");
    }

    @Test
    @DisplayName("A single socket multiplexes several subscriptions, each tagged with its own externalId")
    void oneSocketMultiplexesMultipleSubscriptions() throws Exception {
        registerSubscription("ext-mux-a", SubscriptionType.FAILOVER);
        registerSubscription("ext-mux-b", SubscriptionType.FAILOVER);

        List<TextMessage> outbox = synchronizedList();
        // Attach the first subscription via the handshake path, the second via a runtime "subscribe".
        WebSocketSession ws = mockSession("mux-1", listenUri("ext-mux-a"), principal("tenant-mux"), outbox);
        handler.afterConnectionEstablished(ws);
        handler.handleTextMessage(ws, new TextMessage(
                JSON.writeValueAsString(Map.of("action", "subscribe", "externalIds", List.of("ext-mux-b")))));

        produceFanout(FANOUT_TOPIC, "ext-mux-a", datapointMessage("tenant-mux", "ts-a", "10"));
        produceFanout(FANOUT_TOPIC, "ext-mux-b", datapointMessage("tenant-mux", "ts-b", "20"));

        await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(200)).until(() ->
                timeseriesIdsFor(outbox, "ext-mux-a").contains("ts-a")
                        && timeseriesIdsFor(outbox, "ext-mux-b").contains("ts-b"));

        // Cross-check the streams stayed separate on the one connection.
        assertThat(timeseriesIdsFor(outbox, "ext-mux-a")).containsExactly("ts-a");
        assertThat(timeseriesIdsFor(outbox, "ext-mux-b")).containsExactly("ts-b");
    }

    @Test
    @DisplayName("A durable subscription retains backlog and redelivers unacked messages across a reconnect")
    void durableSubscriptionRetainsBacklogAcrossReconnect() throws Exception {
        String externalId = "ext-dur";
        registerSubscription(externalId, SubscriptionType.FAILOVER);
        // Mirror SubscriptionService: create the durable cursor (at latest) with its filter.key before
        // any consumer or producer touches the topic.
        createDurableSubscription(externalId);

        // Produced while nobody is connected: a durable subscription must retain it.
        produceFanout(FANOUT_TOPIC, externalId, datapointMessage("tenant-dur", "ts-d", "100"));

        List<TextMessage> firstOutbox = synchronizedList();
        WebSocketSession ws1 = mockSession("dur-1", listenUri(externalId), principal("tenant-dur"), firstOutbox);
        handler.afterConnectionEstablished(ws1);
        await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(200)).until(() ->
                valuesFor(firstOutbox, externalId).contains("100"));

        // Disconnect WITHOUT acking, then produce more backlog while disconnected.
        handler.afterConnectionClosed(ws1, org.springframework.web.socket.CloseStatus.NORMAL);
        produceFanout(FANOUT_TOPIC, externalId, datapointMessage("tenant-dur", "ts-d", "200"));

        List<TextMessage> secondOutbox = synchronizedList();
        WebSocketSession ws2 = mockSession("dur-2", listenUri(externalId), principal("tenant-dur"), secondOutbox);
        handler.afterConnectionEstablished(ws2);

        // The reconnect resumes the durable cursor: the unacked "100" is redelivered and "200" (produced
        // while disconnected) is delivered too.
        await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(200)).until(() ->
                valuesFor(secondOutbox, externalId).containsAll(Set.of("100", "200")));
    }

    @Test
    @DisplayName("An unknown subscription yields a not-found error frame, not a torn-down connection")
    void unknownSubscriptionSendsErrorFrame() throws Exception {
        List<TextMessage> outbox = synchronizedList();
        WebSocketSession ws = mockSession("missing-1", listenUri("ext-missing"), principal("tenant-x"), outbox);
        handler.afterConnectionEstablished(ws);

        await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(100)).until(() ->
                hasErrorFrame(outbox, "ext-missing", "not-found"));
    }

    @Test
    @DisplayName("A caller lacking read access to the subscription's dataset is refused with a 'forbidden' frame")
    void deniesAttachWhenCallerLacksDatasetReadAccess() throws Exception {
        // The subscription exists and binds a timeseries in dataset 42...
        registerSubscription("ext-denied", SubscriptionType.FAILOVER);
        when(subscriptionRepository.findTimeseriesDatasetIds(anyLong())).thenReturn(List.of(42L));

        List<TextMessage> outbox = synchronizedList();
        // ...but this caller holds no dataset grants at all, so attach must be refused and no
        // Pulsar consumer is created for it.
        WebSocketSession ws = mockSession("denied-1", listenUri("ext-denied"),
                principalWithoutDatasetGrants("tenant-denied"),
                outbox);
        handler.afterConnectionEstablished(ws);

        await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(100)).until(() ->
                hasErrorFrame(outbox, "ext-denied", "forbidden"));
    }

    // ---- helpers -------------------------------------------------------------------------------

    private SubscriptionEntity registerSubscription(String externalId, SubscriptionType type) {
        SubscriptionEntity entity = new SubscriptionEntity();
        entity.setExternalId(externalId); // also derives externalIdHash
        entity.setName(externalId);
        entity.setSubscriptionType(type);
        registry.put(entity.getExternalIdHash(), entity);
        return entity;
    }

    private void createDurableSubscription(String externalId) throws Exception {
        pulsarAdmin.topics().createSubscription(FANOUT_TOPIC, externalId, MessageId.latest);
        pulsarAdmin.topics().updateSubscriptionProperties(FANOUT_TOPIC, externalId,
                Map.of(TopicNames.SUBSCRIPTION_FILTER_KEY_PROP, externalId));
    }

    private static URI listenUri(String... externalIds) {
        return URI.create(SubscriptionWebSocketHandler.LISTEN_PATH_PREFIX + "/" + String.join("/", externalIds));
    }

    private static List<TextMessage> synchronizedList() {
        return java.util.Collections.synchronizedList(new ArrayList<>());
    }

    /** All timeseries item externalIds delivered under the given subscription externalId. */
    private Set<String> timeseriesIdsFor(List<TextMessage> outbox, String subscriptionExternalId) {
        Set<String> ids = new HashSet<>();
        for (TextMessage frame : snapshot(outbox)) {
            JsonNode node = parse(frame);
            if (!subscriptionExternalId.equals(node.path("subscriptionExternalId").asString(null))) continue;
            for (JsonNode message : node.path("messages")) {
                for (JsonNode item : message.path("payload").path("items")) {
                    String id = item.path("externalId").asString(null);
                    if (id != null) ids.add(id);
                }
            }
        }
        return ids;
    }

    /** All datapoint values delivered under the given subscription externalId. */
    private Set<String> valuesFor(List<TextMessage> outbox, String subscriptionExternalId) {
        Set<String> values = new HashSet<>();
        for (TextMessage frame : snapshot(outbox)) {
            JsonNode node = parse(frame);
            if (!subscriptionExternalId.equals(node.path("subscriptionExternalId").asString(null))) continue;
            for (JsonNode message : node.path("messages")) {
                for (JsonNode item : message.path("payload").path("items")) {
                    for (JsonNode dp : item.path("datapoints")) {
                        String value = dp.path("value").asString(null);
                        if (value != null) values.add(value);
                    }
                }
            }
        }
        return values;
    }

    private Set<String> subscriptionExternalIds(List<TextMessage> outbox) {
        Set<String> ids = new HashSet<>();
        for (TextMessage frame : snapshot(outbox)) {
            String id = parse(frame).path("subscriptionExternalId").asString(null);
            if (id != null) ids.add(id);
        }
        return ids;
    }

    private boolean hasErrorFrame(List<TextMessage> outbox, String externalId, String reason) {
        for (TextMessage frame : snapshot(outbox)) {
            JsonNode node = parse(frame);
            if (node.path("error").asBoolean(false)
                    && externalId.equals(node.path("subscriptionExternalId").asString(null))
                    && reason.equals(node.path("reason").asString(null))) {
                return true;
            }
        }
        return false;
    }

}
