// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.websocket;

import ai.intellistream.datahub.helpers.text.ExternalIds;
import ai.intellistream.datahub.api.datasecurity.DatasetPermissions;
import ai.intellistream.datahub.api.datasecurity.StreamAccessAuthorizer;
import ai.intellistream.datahub.api.responses.DataWrapperMessage;
import ai.intellistream.datahub.jpa.domains.SubscriptionEntity;
import ai.intellistream.datahub.pulsar.TopicNames;
import ai.intellistream.datahub.repositories.subscription.SubscriptionRepository;
import ai.intellistream.datahub.tenant.TenantContext;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.apache.pulsar.client.api.*;
import org.jspecify.annotations.NonNull;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.PingMessage;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URLDecoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.*;

/**
 * Proxies one or more Pulsar consumers over a WebSocket so a client can stream datapoints from
 * several subscriptions' fan-out topics at once and drive acks/nacks from their side.
 * <p>
 * Protocol:
 * <ul>
 *     <li>Connect: {@code ws(s)://<host>/timeseries/datapoints/subscription/listen/{id1}/{id2}/...}
 *         with {@code Authorization: Bearer <jwt>}. The path segments after {@code .../listen/} seed
 *         the initial set of subscription externalIds (it may be empty — the client can subscribe
 *         dynamically instead). The handshake is authenticated by the regular OAuth2 resource
 *         server filter.</li>
 *     <li>Client → server interest: {@code { "action": "subscribe" | "unsubscribe" | "set", "externalIds": ["..."] }}
 *         to add, drop, or replace the streamed subscriptions at runtime.</li>
 *     <li>Server → client: {@code { "subscriptionExternalId": "<id>", "messages": [{ "messageId": "&lt;b64&gt;", "payload": &lt;DataWrapperMessage&gt; }, ...] }}
 *         — one frame per batch window per subscription.</li>
 *     <li>Client → server ack: {@code { "action": "ack" | "nack", "messageIds": ["...", "..."] }}.
 *         The opaque {@code messageId} encodes its subscription, so the ack is routed to the right
 *         consumer.</li>
 *     <li>Server → client error: {@code { "error": true, "subscriptionExternalId": "<id>", "reason": "not-found" }}
 *         when a requested subscription can't be attached.</li>
 * </ul>
 * Subscription model: the unit of subscription is the subscription entity's externalId, never the
 * individual timeseries. One Pulsar subscription (named after the externalId) covers <em>every</em>
 * timeseries bound to that subscription, and all WebSocket clients of that subscription share it
 * (they differ only by {@code consumerName}). Every subscription of a tenant reads the same
 * per-tenant fan-out topic ({@code persistent://<pulsar-tenant>/subscriptions/fanout}); the stateless
 * consumer ({@code BatchedDatapointsListener}) republishes each datapoint once per interested
 * subscription, keyed by externalId and ordered per timeseries. The broker-side
 * {@code SubscriptionKeyEntryFilter} (in datahub-pulsar-filter; {@code filter.key} = externalId) then
 * hands each subscription only its own messages. A single socket multiplexes by
 * opening one Pulsar consumer per subscribed externalId, so the Pulsar subscription count scales with
 * the number of subscription entities, not the number of timeseries.
 * <p>
 * Per-subscription Pulsar subscription type is Failover/Key_Shared. No load-balancer session
 * affinity is required: the durable cursor lives in Pulsar, so a reconnect re-subscribes and resumes
 * on whichever instance it lands on.
 */
@Component
@Slf4j
public class SubscriptionWebSocketHandler extends TextWebSocketHandler {

    /** URL prefix this handler is registered under; path segments after it are subscription ids. */
    static final String LISTEN_PATH_PREFIX = "/timeseries/datapoints/subscription/listen";

    private static final int BATCH_MAX_MESSAGES = 500;
    private static final int BATCH_TIMEOUT_MS = 500;
    private static final int WS_SEND_TIME_LIMIT_MS = 10_000;
    private static final int WS_SEND_BUFFER_LIMIT = 1024 * 1024;
    /**
     * How often the server sends a WebSocket PING to each open session. Must be shorter than the
     * container's {@code maxSessionIdleTimeout} (45s) so the client's PONG reply resets the idle
     * timer and keeps the connection alive during long Pulsar silence.
     */
    private static final int PING_INTERVAL_SECONDS = 15;

    private final PulsarClient pulsarClient;
    private final TopicNames topicNames;
    private final SubscriptionRepository subscriptionRepository;
    private final JsonMapper jsonMapper;
    private final StreamAccessAuthorizer accessAuthorizer;
    private final WebSocketConnectionLimiter connectionLimiter;
    /** Who each open session belongs to, so the ping can refresh it and close can free it. */
    private final Map<String, ConnectionOwner> owners = new ConcurrentHashMap<>();

    /** The identity a socket is charged to. */
    private record ConnectionOwner(String tenantId, String subject) {
    }


    // Per-connection state — instance-local by design. A WebSocket is a TCP connection bound to one instance. No session affinity is required: each subscription's durable cursor lives in Pulsar, so a reconnect re-subscribes and resumes on whichever instance it lands on.
    private final Map<String, SubscriptionListenSession> sessions = new ConcurrentHashMap<>();
    private final Map<String, WebSocketSession> wsSessions = new ConcurrentHashMap<>();
    // One virtual thread per connection's blocking receive loop. The loop blocks on Pulsar
    // batchReceive()/sendMessage() and unmounts its carrier while waiting, so idle connections
    // cost a small heap-resident stack rather than a full OS thread. No pinning concern: the
    // Pulsar client path is pure Java (no JNI) and synchronized no longer pins on Java 25 (JEP 491).
    private final ExecutorService receiveExecutor = Executors.newThreadPerTaskExecutor(
            Thread.ofVirtual().name("subscription-listen-receive-", 0).factory());
    private final ScheduledExecutorService pingScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "subscription-listen-ping");
        t.setDaemon(true);
        return t;
    });

    public SubscriptionWebSocketHandler(PulsarClient pulsarClient,
                                        TopicNames topicNames,
                                        SubscriptionRepository subscriptionRepository,
                                        JsonMapper jsonMapper,
                                        StreamAccessAuthorizer accessAuthorizer,
                                        WebSocketConnectionLimiter connectionLimiter) {
        this.pulsarClient = pulsarClient;
        this.topicNames = topicNames;
        this.subscriptionRepository = subscriptionRepository;
        this.jsonMapper = jsonMapper;
        this.accessAuthorizer = accessAuthorizer;
        this.connectionLimiter = connectionLimiter;
        pingScheduler.scheduleAtFixedRate(this::sendPings,
                PING_INTERVAL_SECONDS, PING_INTERVAL_SECONDS, TimeUnit.SECONDS);
    }

    /**
     * Send a PING frame to every open WS session. A well-behaved client auto-replies with PONG,
     * which the container treats as activity and resets the idle timer. If a client has died
     * silently, the ping send fails (closed session) or the container's idle timer expires
     * because no PONG arrives — either way, {@link #afterConnectionClosed} runs and cleans up.
     */
    private void sendPings() {
        if (wsSessions.isEmpty()) return;
        ByteBuffer empty = ByteBuffer.allocate(0);
        for (Map.Entry<String, WebSocketSession> entry : wsSessions.entrySet()) {
            WebSocketSession session = entry.getValue();
            if (!session.isOpen()) continue;
            try {
                session.sendMessage(new PingMessage(empty));
                // Doubles as the liveness signal the connection registry counts by, so a socket
                // this instance still holds is never mistaken for one left by a dead instance.
                ConnectionOwner owner = owners.get(entry.getKey());
                if (owner != null) {
                    connectionLimiter.heartbeat(owner.tenantId(), owner.subject(), entry.getKey());
                }
            } catch (Exception e) {
                log.warn("Ping failed for session {} ({}); leaving container to clean up", entry.getKey(), e.getMessage());
            }
        }
    }

    @Override
    public void afterConnectionEstablished(@NonNull WebSocketSession rawSession) throws Exception {
        String tenantId = extractTenantId(rawSession.getPrincipal());
        if (tenantId == null) {
            closeQuietly(rawSession, CloseStatus.POLICY_VIOLATION.withReason("Missing tenant context"));
            return;
        }

        // Concurrency cap, checked once the tenant is known and before any Pulsar consumer exists,
        // so a refused connection costs the broker nothing.
        String subject = extractSubject(rawSession.getPrincipal());
        var refusal = connectionLimiter.register(tenantId, subject, rawSession.getId());
        if (refusal.isPresent()) {
            log.info("Subscription handshake refused: {} limit of {} reached for tenant {}",
                    refusal.get().scope(), refusal.get().limit(), tenantId);
            sendLimitError(rawSession, refusal.get());
            closeQuietly(rawSession, CloseStatus.POLICY_VIOLATION.withReason(
                    "WebSocket connection limit reached"));
            return;
        }
        owners.put(rawSession.getId(), new ConnectionOwner(tenantId, subject));

        List<String> externalIds = parseExternalIds(rawSession);
        log.info("Subscription WS connect: tenant={} session={} initialSubscriptions={}",
                tenantId, rawSession.getId(), externalIds);

        // Wrap the session to make concurrent sends from sibling receive loops + close safe.
        WebSocketSession safeSession = new ConcurrentWebSocketSessionDecorator(
                rawSession, WS_SEND_TIME_LIMIT_MS, WS_SEND_BUFFER_LIMIT);

        // Force the idle timeout onto the native Jakarta WebSocket session. The factory-bean default
        // doesn't always propagate, so set it explicitly here as a belt-and-braces measure.
        try {
            if (rawSession instanceof org.springframework.web.socket.adapter.standard.StandardWebSocketSession std) {
                std.getNativeSession().setMaxIdleTimeout(WebSocketConfig.MAX_SESSION_IDLE_MILLIS);
            }
        } catch (Exception e) {
            log.warn("Failed to set idle timeout on session {}: {}", rawSession.getId(), e.getMessage());
        }

        SubscriptionListenSession listen = new SubscriptionListenSession(safeSession, jsonMapper, receiveExecutor);
        sessions.put(rawSession.getId(), listen);
        wsSessions.put(rawSession.getId(), safeSession);

        DatasetPermissions permissions = accessAuthorizer.permissionsOf(tenantId, rawSession.getPrincipal());

        // Attach the subscriptions named in the path. The set may legitimately be empty — the client
        // can add subscriptions with a "subscribe" message later.
        for (String externalId : externalIds) {
            attachSubscription(listen, tenantId, externalId, safeSession, permissions);
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        SubscriptionListenSession listen = sessions.get(session.getId());
        WebSocketSession safeSession = wsSessions.get(session.getId());
        if (listen == null || safeSession == null) {
            log.debug("Received text on session {} but no listen session found", session.getId());
            return;
        }
        try {
            JsonNode node = jsonMapper.readTree(message.getPayload());
            String action = node.path("action").asString(null);
            if (action == null) {
                log.warn("Subscription WS message missing 'action' field on session {}", session.getId());
                return;
            }
            switch (action) {
                case "ack" -> listen.acknowledge(readStringArray(node, "messageIds"));
                case "nack" -> listen.negativeAcknowledge(readStringArray(node, "messageIds"));
                case "subscribe", "add" -> {
                    String tenantId = extractTenantId(session.getPrincipal());
                    if (tenantId != null) {
                        DatasetPermissions permissions = accessAuthorizer.permissionsOf(tenantId, session.getPrincipal());
                        for (String id : readStringArray(node, "externalIds")) {
                            attachSubscription(listen, tenantId, id, safeSession, permissions);
                        }
                    }
                }
                case "unsubscribe", "remove" -> readStringArray(node, "externalIds").forEach(listen::removeStream);
                case "set" -> {
                    String tenantId = extractTenantId(session.getPrincipal());
                    if (tenantId != null) {
                        DatasetPermissions permissions = accessAuthorizer.permissionsOf(tenantId, session.getPrincipal());
                        Set<String> desired = new LinkedHashSet<>(readStringArray(node, "externalIds"));
                        for (String ext : listen.subscribedExternalIds()) {
                            if (!desired.contains(ext)) listen.removeStream(ext);
                        }
                        for (String ext : desired) attachSubscription(listen, tenantId, ext, safeSession, permissions);
                    }
                }
                default -> log.warn("Unknown subscription WS action '{}' on session {}", action, session.getId());
            }
        } catch (Exception e) {
            log.error("Failed to parse subscription WS message on session {}: {}", session.getId(), e.getMessage());
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        wsSessions.remove(session.getId());
        ConnectionOwner owner = owners.remove(session.getId());
        if (owner != null) {
            connectionLimiter.release(owner.tenantId(), owner.subject(), session.getId());
        }
        SubscriptionListenSession listen = sessions.remove(session.getId());
        if (listen != null) listen.stop();
        log.info("Subscription WS closed: session={} status={}", session.getId(), status);
    }

    @PreDestroy
    public void shutdown() {
        pingScheduler.shutdownNow();
        wsSessions.clear();
        sessions.values().forEach(SubscriptionListenSession::stop);
        sessions.clear();
        receiveExecutor.shutdownNow();
        try {
            receiveExecutor.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Resolve a subscription, build its Pulsar consumer, and attach it to the connection. No-op if
     * the subscription is already attached. Failures (unknown id, Pulsar error) are reported to the
     * client as an error frame and skipped — they never tear down the whole connection.
     */
    private void attachSubscription(SubscriptionListenSession listen, String tenantId,
                                    String externalId, WebSocketSession session, DatasetPermissions permissions) {
        if (externalId == null || externalId.isBlank() || listen.hasStream(externalId)) return;

        // One socket multiplexes many subscriptions, and each is a durable consumer the broker holds
        // open. Counted in-session, which is exact — the socket lives on this instance. Over the cap
        // is answered and the socket stays open: the other subscriptions on it are still valid.
        int maxStreams = connectionLimiter.maxSubscriptionsPerSocket();
        if (maxStreams > 0 && listen.streamCount() >= maxStreams) {
            log.info("Refusing subscription {} on session {}: at the {} per-socket limit",
                    externalId, session.getId(), maxStreams);
            sendError(session, externalId, "subscription-limit-reached");
            return;
        }

        Optional<SubscriptionEntity> maybe;
        try {
            maybe = loadSubscription(tenantId, externalId);
        } catch (Exception e) {
            log.error("Failed to resolve subscription {} for tenant {}: {}", externalId, tenantId, e.getMessage(), e);
            sendError(session, externalId, "resolve-failed");
            return;
        }
        if (maybe.isEmpty()) {
            log.warn("Subscription {} not found for tenant {}", externalId, tenantId);
            sendError(session, externalId, "not-found");
            return;
        }

        // Dataset ACL: the fan-out streams every timeseries bound to the subscription, so the caller
        // must be able to read all of them (all-or-nothing). Refuse to attach otherwise — without
        // this a tenant user could stream datasets they have no read role for (IDOR).
        if (!accessAuthorizer.canReadSubscription(tenantId, permissions, externalId)) {
            log.warn("Subscription {} denied for tenant {}: caller lacks dataset read access", externalId, tenantId);
            sendError(session, externalId, "forbidden");
            return;
        }

        Consumer<DataWrapperMessage> consumer;
        try {
            consumer = buildPulsarConsumer(tenantId, maybe.get());
        } catch (PulsarClientException e) {
            log.error("Failed to create Pulsar consumer for {}: {}", externalId, e.getMessage(), e);
            sendError(session, externalId, "subscribe-failed");
            return;
        } catch (IllegalStateException e) {
            // Tenant has no pulsar.tenant configured — report it rather than resolving to a shared topic.
            log.error("Cannot subscribe externalId={} for tenant {}: {}", externalId, tenantId, e.getMessage());
            sendError(session, externalId, "no-pulsar-config");
            return;
        }

        if (!listen.addStream(externalId, consumer)) {
            // Lost a race (already attached, or the session is stopping) — don't leak the consumer.
            try {
                consumer.close();
            } catch (PulsarClientException ignored) {
                // nothing to do
            }
        }
    }

    private Consumer<DataWrapperMessage> buildPulsarConsumer(String tenantId, SubscriptionEntity entity) throws PulsarClientException {
        String externalId = entity.getExternalId();
        String topic = topicNames.getSubscriptionFanoutTopicName(tenantId);
        // Consumer name must be unique per connection — Failover picks the lowest-sorted active
        // consumer as primary, so using a nonce in the name keeps reconnects distinguishable in
        // Pulsar's broker logs. KeyShared has the same uniqueness need so multiple worker processes
        // don't collide on consumer name.
        String consumerName = "subscription-ws-" + externalId + "-" + Long.toHexString(System.nanoTime());
        // subscriptionProperties pins the filter key for this logical subscription. The
        // SubscriptionService admin call already set it at create-time; re-applying here is
        // a belt-and-braces guarantee for subscriptions created before this code shipped.
        return pulsarClient.newConsumer(Schema.AVRO(DataWrapperMessage.class))
                .topic(topic)
                .subscriptionName(externalId)
                .subscriptionType(toPulsarSubscriptionType(entity.getSubscriptionType()))
                .consumerName(consumerName)
                .subscriptionProperties(Map.of(TopicNames.SUBSCRIPTION_FILTER_KEY_PROP, externalId))
                .batchReceivePolicy(BatchReceivePolicy.builder()
                        .maxNumMessages(BATCH_MAX_MESSAGES)
                        .timeout(BATCH_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                        .build())
                .subscribe();
    }

    /**
     * Map our internal {@link ai.intellistream.datahub.subscription.SubscriptionType} onto
     * the Pulsar client's enum. Kept tiny and switch-style so a future enum addition fails
     * compilation rather than silently falling through to a default.
     */
    private static SubscriptionType toPulsarSubscriptionType(
            ai.intellistream.datahub.subscription.SubscriptionType type) {
        return switch (type) {
            case FAILOVER -> SubscriptionType.Failover;
            case KEY_SHARED -> SubscriptionType.Key_Shared;
        };
    }

    /**
     * DB-access helper — routes through {@code StatelessRoutingDataSource}, so {@link TenantContext}
     * must be set before the repository call. The JWT-derived tenant id is authoritative.
     */
    @Transactional(readOnly = true)
    protected Optional<SubscriptionEntity> loadSubscription(String tenantId, String externalId) {
        String previous = TenantContext.getTenantId();
        try {
            TenantContext.setTenantId(tenantId);
            long hash = ExternalIds.hash(externalId);
            return subscriptionRepository.findByExternalIdHash(hash);
        } finally {
            if (previous == null) TenantContext.clear();
            else TenantContext.setTenantId(previous);
        }
    }

    /** Read a JSON string array field into a list (missing/non-array → empty). */
    private static List<String> readStringArray(JsonNode node, String field) {
        List<String> out = new ArrayList<>();
        JsonNode arr = node.path(field);
        if (arr.isArray()) {
            for (JsonNode n : arr) out.add(n.asString());
        }
        return out;
    }

    /** Notify the client that a requested subscription couldn't be attached. */
    /** One frame explaining the refusal, so a client sees a reason rather than a bare close code. */
    private void sendLimitError(WebSocketSession session, WebSocketConnectionLimiter.Refusal refusal) {
        try {
            session.sendMessage(new TextMessage(jsonMapper.writeValueAsString(Map.of(
                    "error", Boolean.TRUE,
                    "reason", "websocket-limit-reached",
                    "scope", refusal.scope(),
                    "limit", refusal.limit(),
                    "message", refusal.message()))));
        } catch (Exception e) {
            log.debug("Could not send the limit error frame to {}: {}", session.getId(), e.getMessage());
        }
    }

    /** The JWT {@code sub} behind this connection, or null when the principal is not a JWT. */
    private static String extractSubject(Principal principal) {
        return principal instanceof JwtAuthenticationToken jwtAuth ? jwtAuth.getToken().getSubject() : null;
    }

    private void sendError(WebSocketSession session, String externalId, String reason) {
        try {
            String json = jsonMapper.writeValueAsString(Map.of(
                    "error", Boolean.TRUE,
                    "subscriptionExternalId", externalId,
                    "reason", reason));
            session.sendMessage(new TextMessage(json));
        } catch (Exception e) {
            log.warn("Failed to send error notice for {} on session {}: {}", externalId, session.getId(), e.getMessage());
        }
    }

    /**
     * Path segments after {@code .../subscription/listen/} are the subscription externalIds. An empty
     * tail (no segments) yields an empty list — a valid pure-dynamic connection.
     */
    private static List<String> parseExternalIds(WebSocketSession session) {
        if (session.getUri() == null) return List.of();
        String path = session.getUri().getPath();
        int idx = path.indexOf(LISTEN_PATH_PREFIX);
        if (idx < 0) return List.of();
        String tail = path.substring(idx + LISTEN_PATH_PREFIX.length());
        if (tail.startsWith("/")) tail = tail.substring(1);
        if (tail.isEmpty()) return List.of();
        List<String> ids = new ArrayList<>();
        for (String seg : tail.split("/")) {
            if (seg.isEmpty()) continue;
            ids.add(URLDecoder.decode(seg, StandardCharsets.UTF_8));
        }
        return ids;
    }

    /**
     * Extract the tenant id from the JWT's {@code organization} claim.
     * See {@code SecurityConfig.OrganizationValidator} for claim shape.
     */
    private static String extractTenantId(Principal principal) {
        if (!(principal instanceof JwtAuthenticationToken jwtAuth)) return null;
        Jwt jwt = jwtAuth.getToken();
        Map<String, Object> orgs = jwt.getClaimAsMap("organization");
        if (orgs == null || orgs.isEmpty()) return null;
        Object first = orgs.values().iterator().next();
        if (first instanceof Map<?, ?> details && details.get("id") != null) {
            return details.get("id").toString();
        }
        return null;
    }

    private static void closeQuietly(WebSocketSession session, CloseStatus status) {
        try {
            session.close(status);
        } catch (Exception e) {
            log.warn("Failed to close WS session cleanly: {}", e.getMessage());
        }
    }
}
