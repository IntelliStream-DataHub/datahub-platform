// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.websocket;

import ai.intellistream.datahub.api.datasecurity.StreamAccessAuthorizer;
import ai.intellistream.datahub.pulsar.EventAction;
import ai.intellistream.datahub.pulsar.TopicNames;
import ai.intellistream.datahub.repositories.node.TimeseriesRepository;
import ai.intellistream.datahub.repositories.subscription.SubscriptionRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.JsonNode;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Integration tests for {@link DatapointListenWebSocketHandler} (the browser live-tail endpoint)
 * against a real Pulsar broker.
 *
 * <p>Unlike the subscription endpoint, this handler does all routing in-process: one non-durable,
 * latest-position consumer per connection tails the whole all-datapoints firehose and narrows it to
 * the connection's tenant + interest set + {@code DATAPOINTS/CREATE} events. These tests assert that
 * in-process filtering, the non-durable "latest only" semantics, and runtime interest updates.
 */
class DatapointListenWebSocketHandlerIT extends AbstractPulsarWebSocketIT {

    private DatapointListenWebSocketHandler handler;
    private JwtDecoder jwtDecoder;

    @BeforeEach
    void setUp() {
        jwtDecoder = mock(JwtDecoder.class);
        TopicNames topicNames = topicNames();
        StreamAccessAuthorizer authorizer = new StreamAccessAuthorizer(
                mock(TimeseriesRepository.class), mock(SubscriptionRepository.class), testGroupsResolver());
        handler = new DatapointListenWebSocketHandler(pulsarClient, topicNames, jwtDecoder, JSON, authorizer);
    }

    @AfterEach
    void tearDown() {
        if (handler != null) handler.shutdown();
    }

    @Test
    @DisplayName("Tail forwards only the connection's tenant + interest + CREATE datapoints")
    void tailForwardsOnlyMatchingTenantInterestAndCreate() throws Exception {
        String token = "tok-filter";
        stubToken(token, "tenant-tail");

        List<TextMessage> outbox = synchronizedList();
        WebSocketSession ws = mockSession("tail-1", tailUri(token, "ts-a"), null, outbox);
        handler.afterConnectionEstablished(ws);

        // Three rejects produced first, the single accept last: if any filter were broken the rejects
        // (delivered in order on the one consumer) would already be visible once the accept arrives.
        produceAllDatapoints("tenant-tail", EventAction.CREATE, "ts-b", "rej-interest");   // not in interest
        produceAllDatapoints("tenant-other", EventAction.CREATE, "ts-a", "rej-tenant");    // wrong tenant
        produceAllDatapoints("tenant-tail", EventAction.UPDATE, "ts-a", "rej-action");     // not CREATE
        produceAllDatapoints("tenant-tail", EventAction.CREATE, "ts-a", "accept");         // the only match

        await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(150)).until(() ->
                forwardedPairs(outbox).contains(List.of("ts-a", "accept")));

        assertThat(forwardedPairs(outbox)).containsExactly(List.of("ts-a", "accept"));
    }

    @Test
    @DisplayName("Tail is non-durable and latest-only: messages published before connect are not seen")
    void tailIsNonDurableLatestOnly() throws Exception {
        String token = "tok-latest";
        stubToken(token, "tenant-latest");

        // Published BEFORE the consumer exists: a latest-position consumer must not see it.
        produceAllDatapoints("tenant-latest", EventAction.CREATE, "ts-l", "before");

        List<TextMessage> outbox = synchronizedList();
        WebSocketSession ws = mockSession("latest-1", tailUri(token, "ts-l"), null, outbox);
        handler.afterConnectionEstablished(ws);

        produceAllDatapoints("tenant-latest", EventAction.CREATE, "ts-l", "after");

        await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(150)).until(() ->
                forwardedPairs(outbox).contains(List.of("ts-l", "after")));

        assertThat(forwardedPairs(outbox)).doesNotContain(List.of("ts-l", "before"));
    }

    @Test
    @DisplayName("A runtime 'set' interest update starts forwarding a previously-ignored timeseries")
    void runtimeInterestUpdateStartsForwarding() throws Exception {
        String token = "tok-interest";
        stubToken(token, "tenant-int");

        List<TextMessage> outbox = synchronizedList();
        // Connect with an empty interest set: nothing should ever be forwarded.
        WebSocketSession ws = mockSession("interest-1", tailUri(token, null), null, outbox);
        handler.afterConnectionEstablished(ws);

        produceAllDatapoints("tenant-int", EventAction.CREATE, "ts-i", "ignored");
        // Empty interest forwards nothing regardless of timing, so a bounded wait that finds nothing
        // is a sound assertion.
        assertThat(appearsWithin(outbox, Duration.ofSeconds(2), "ts-i", "ignored")).isFalse();

        // Add interest at runtime, then publish again.
        handler.handleTextMessage(ws, new TextMessage(
                JSON.writeValueAsString(Map.of("action", "set", "externalIds", List.of("ts-i")))));
        produceAllDatapoints("tenant-int", EventAction.CREATE, "ts-i", "forwarded");

        await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(150)).until(() ->
                forwardedPairs(outbox).contains(List.of("ts-i", "forwarded")));
        assertThat(forwardedPairs(outbox)).doesNotContain(List.of("ts-i", "ignored"));
    }

    @Test
    @DisplayName("Handshake without the DATAHUB_ACCESS role is rejected before any tail starts")
    void handshakeRejectedWithoutAccessRole() throws Exception {
        String token = "tok-norole";
        when(jwtDecoder.decode(token)).thenReturn(jwtWithRealmRoles("tenant-norole", List.of("SOME_OTHER_ROLE")));

        List<TextMessage> outbox = synchronizedList();
        WebSocketSession ws = mockSession("norole-1", tailUri(token, "ts-a"), null, outbox);
        handler.afterConnectionEstablished(ws);

        // The connection is closed and nothing is ever streamed.
        verify(ws).close(any(CloseStatus.class));
        assertThat(outbox).isEmpty();
    }

    @Test
    @DisplayName("A caller with DATAHUB_ACCESS but no dataset read role tails nothing")
    void tailForwardsNothingWithoutDatasetReadAccess() throws Exception {
        String token = "tok-noread";
        when(jwtDecoder.decode(token)).thenReturn(jwtWithRealmRoles("tenant-noread", List.of("DATAHUB_ACCESS")));

        List<TextMessage> outbox = synchronizedList();
        WebSocketSession ws = mockSession("noread-1", tailUri(token, "ts-a"), null, outbox);
        handler.afterConnectionEstablished(ws);

        produceAllDatapoints("tenant-noread", EventAction.CREATE, "ts-a", "secret");
        // The caller has no readable datasets, so the requested interest is dropped and nothing is
        // forwarded even though a matching datapoint is produced.
        assertThat(appearsWithin(outbox, Duration.ofSeconds(2), "ts-a", "secret")).isFalse();
    }

    // ---- helpers -------------------------------------------------------------------------------

    private void stubToken(String token, String tenantId) {
        // Each test only ever hands the handler this exact token (via the handshake query string),
        // so a single specific stub is all that is needed.
        when(jwtDecoder.decode(token)).thenReturn(jwt(tenantId));
    }

    private static URI tailUri(String token, String externalIdsCsv) {
        String query = "token=" + token;
        if (externalIdsCsv != null) query += "&externalIds=" + externalIdsCsv;
        return URI.create("/timeseries/datapoints/listen?" + query);
    }

    private static List<TextMessage> synchronizedList() {
        return java.util.Collections.synchronizedList(new ArrayList<>());
    }

    /** Each forwarded datapoint as an [externalId, value] pair across all frames. */
    private Set<List<String>> forwardedPairs(List<TextMessage> outbox) {
        Set<List<String>> pairs = new HashSet<>();
        for (TextMessage frame : snapshot(outbox)) {
            for (JsonNode dp : parse(frame).path("datapoints")) {
                pairs.add(List.of(dp.path("externalId").asString(""), dp.path("value").asString("")));
            }
        }
        return pairs;
    }

    /** Poll for up to {@code timeout}; return whether the pair was ever forwarded. */
    private boolean appearsWithin(List<TextMessage> outbox, Duration timeout, String externalId, String value) {
        try {
            await().atMost(timeout).pollInterval(Duration.ofMillis(150))
                    .until(() -> forwardedPairs(outbox).contains(List.of(externalId, value)));
            return true;
        } catch (org.awaitility.core.ConditionTimeoutException timedOut) {
            return false;
        }
    }

}
