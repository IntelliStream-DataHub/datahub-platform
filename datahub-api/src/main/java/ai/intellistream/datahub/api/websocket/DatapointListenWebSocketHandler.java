// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.websocket;

import ai.intellistream.datahub.api.datasecurity.DatasetPermissions;
import ai.intellistream.datahub.api.datasecurity.StreamAccessAuthorizer;
import ai.intellistream.datahub.api.responses.DataWrapperBin;
import ai.intellistream.datahub.pulsar.TopicNames;
import ai.intellistream.datahub.tenant.TenantContext;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.apache.pulsar.client.api.*;
import org.jspecify.annotations.NonNull;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.PingMessage;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

/**
 * Streams live datapoints to a browser over a WebSocket, tailing the global all-datapoints topic
 * and filtering — per connection — down to the tenant (from the JWT) and the set of timeseries the
 * client asks for. No subscription entity is involved: each connection gets its own non-durable
 * Pulsar consumer reading from {@code latest}, so nothing is provisioned or left behind.
 * <p>
 * Protocol:
 * <ul>
 *     <li>Connect: {@code ws(s)://<host>/timeseries/datapoints/listen?token=<jwt>}. A browser
 *         {@code WebSocket} can't set an {@code Authorization} header, so the access token rides in
 *         the {@code token} query parameter; this handler validates it with the resource server's
 *         {@link JwtDecoder}. An optional {@code externalIds=a,b,c} param seeds the interest set.</li>
 *     <li>Client → server: {@code { "action": "set" | "subscribe" | "unsubscribe", "externalIds": ["..."] }}
 *         to change which timeseries are streamed. {@code set} (the default) replaces the set.</li>
 *     <li>Server → client: {@code { "datapoints": [{ "externalId", "valueType", "timestamp", "value" }, ...] }}
 *         — one frame per Pulsar batch window, carrying only the matched points.</li>
 * </ul>
 * Subscription model: one non-durable consumer per connection tails the entire all-datapoints topic
 * (every tenant, every timeseries) and narrows in-process to this connection's tenant and requested
 * interest set. There is no Pulsar subscription per timeseries or per subscription entity, and no
 * broker-side filtering: the consumer reads the firehose and drops what the client didn't ask for.
 * Contrast {@link SubscriptionWebSocketHandler}, which uses durable per-subscription cursors on
 * per-tenant fan-out topics with broker-side key filtering.
 * <p>
 * The handshake path is {@code permitAll} in {@code SecurityConfig}; authentication is done here so
 * the token can come from the query string rather than a header.
 */
@Component
@Slf4j
public class DatapointListenWebSocketHandler extends TextWebSocketHandler {

    private static final int BATCH_MAX_MESSAGES = 500;
    private static final int BATCH_MAX_BYTES = 5 * 1024 * 1024;
    private static final int BATCH_TIMEOUT_MS = 300;
    private static final int WS_SEND_TIME_LIMIT_MS = 10_000;
    private static final int WS_SEND_BUFFER_LIMIT = 1024 * 1024;
    /** See {@link SubscriptionWebSocketHandler} — keep shorter than the container idle timeout. */
    private static final int PING_INTERVAL_SECONDS = 15;

    private final PulsarClient pulsarClient;
    private final TopicNames topicNames;
    private final JwtDecoder jwtDecoder;
    private final JsonMapper jsonMapper;
    private final StreamAccessAuthorizer accessAuthorizer;
    private final WebSocketConnectionLimiter connectionLimiter;
    /** Who each open session belongs to, so the ping can refresh it and close can free it. */
    private final Map<String, ConnectionOwner> owners = new ConcurrentHashMap<>();

    /** The identity a socket is charged to. */
    private record ConnectionOwner(String tenantId, String subject) {
    }


    // Per-connection state — instance-local by design (a WebSocket is bound to one instance, so the
    // LB must route upgrades with session affinity). On instance failure the client reconnects.
    private final Map<String, DatapointListenSession> sessions = new ConcurrentHashMap<>();
    private final Map<String, WebSocketSession> wsSessions = new ConcurrentHashMap<>();
    private final ExecutorService receiveExecutor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r);
        t.setDaemon(true);
        t.setName("datapoint-listen-receive");
        return t;
    });
    private final ScheduledExecutorService pingScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "datapoint-listen-ping");
        t.setDaemon(true);
        return t;
    });

    public DatapointListenWebSocketHandler(PulsarClient pulsarClient,
                                           TopicNames topicNames,
                                           JwtDecoder jwtDecoder,
                                           JsonMapper jsonMapper,
                                           StreamAccessAuthorizer accessAuthorizer,
                                           WebSocketConnectionLimiter connectionLimiter) {
        this.pulsarClient = pulsarClient;
        this.topicNames = topicNames;
        this.jwtDecoder = jwtDecoder;
        this.jsonMapper = jsonMapper;
        this.accessAuthorizer = accessAuthorizer;
        this.connectionLimiter = connectionLimiter;
        pingScheduler.scheduleAtFixedRate(this::sendPings,
                PING_INTERVAL_SECONDS, PING_INTERVAL_SECONDS, TimeUnit.SECONDS);
    }

    @Override
    public void afterConnectionEstablished(@NonNull WebSocketSession rawSession) throws Exception {
        // jwtDecoder.decode() runs OrganizationValidator, which sets TenantContext as a side
        // effect. This callback runs on a pooled WS container thread AFTER the handshake request
        // (and thus RequestStateCleanupFilter) has finished, so clear the ThreadLocal here or
        // the thread returns to the pool carrying this tenant's id. Nothing below reads
        // TenantContext — the tenant travels explicitly via extractTenantId(jwt).
        try {
            establishConnection(rawSession);
        } finally {
            TenantContext.clear();
        }
    }

    private void establishConnection(WebSocketSession rawSession) {
        URI uri = rawSession.getUri();
        if (uri == null) {
            closeQuietly(rawSession, CloseStatus.POLICY_VIOLATION.withReason("Missing handshake URI"));
            return;
        }

        String token = queryParam(uri, "token");
        if (token == null || token.isBlank()) {
            closeQuietly(rawSession, CloseStatus.POLICY_VIOLATION.withReason("Missing access token"));
            return;
        }

        Jwt jwt;
        try {
            jwt = jwtDecoder.decode(token);
        } catch (JwtException e) {
            log.warn("Datapoint-listen handshake rejected: invalid token ({})", e.getMessage());
            closeQuietly(rawSession, CloseStatus.POLICY_VIOLATION.withReason("Invalid access token"));
            return;
        }

        // This handshake path is permitAll in SecurityConfig (the token rides in the query string, so
        // no filter-chain role gate runs), so enforce the baseline DATAHUB_ACCESS role here. A token
        // with a valid organization claim but without this role must not be able to stream datapoints.
        if (!StreamAccessAuthorizer.hasAccessRole(jwt)) {
            log.warn("Datapoint-listen handshake rejected: token lacks {} role",
                    StreamAccessAuthorizer.REQUIRED_ACCESS_ROLE);
            closeQuietly(rawSession, CloseStatus.POLICY_VIOLATION.withReason("Missing required role"));
            return;
        }

        String tenantId = extractTenantId(jwt);
        if (tenantId == null) {
            closeQuietly(rawSession, CloseStatus.POLICY_VIOLATION.withReason("Missing tenant context"));
            return;
        }

        // Concurrency cap, checked after authentication (so there is an identity to charge) and
        // before the Pulsar consumer is built (so a refused connection costs the broker nothing).
        var refusal = connectionLimiter.register(tenantId, jwt.getSubject(), rawSession.getId());
        if (refusal.isPresent()) {
            log.info("Datapoint-listen handshake refused: {} limit of {} reached for tenant {}",
                    refusal.get().scope(), refusal.get().limit(), tenantId);
            sendLimitError(rawSession, refusal.get());
            closeQuietly(rawSession, CloseStatus.POLICY_VIOLATION.withReason(
                    "WebSocket connection limit reached"));
            return;
        }
        owners.put(rawSession.getId(), new ConnectionOwner(tenantId, jwt.getSubject()));

        // Dataset ACL: narrow the requested interest set to timeseries whose dataset the caller may
        // read, so a caller can only live-tail data they are authorised for (matching the REST reads).
        DatasetPermissions permissions = accessAuthorizer.permissionsOf(tenantId, jwt);
        List<String> requestedInterest = parseExternalIds(queryParam(uri, "externalIds"));
        List<String> initialInterest = accessAuthorizer.readableExternalIds(tenantId, permissions, requestedInterest);
        log.info("Datapoint listen WS connect: tenant={} session={} requestedInterest={} authorizedInterest={}",
                tenantId, rawSession.getId(), requestedInterest.size(), initialInterest.size());

        Consumer<DataWrapperBin> consumer;
        try {
            consumer = buildConsumer();
        } catch (PulsarClientException e) {
            log.error("Failed to create all-datapoints consumer for tenant {}: {}", tenantId, e.getMessage(), e);
            closeQuietly(rawSession, CloseStatus.SERVER_ERROR.withReason("Failed to subscribe to datapoints"));
            return;
        }

        // Wrap the session so concurrent sends from the receive loop + close are safe.
        WebSocketSession safeSession = new ConcurrentWebSocketSessionDecorator(
                rawSession, WS_SEND_TIME_LIMIT_MS, WS_SEND_BUFFER_LIMIT);

        try {
            if (rawSession instanceof org.springframework.web.socket.adapter.standard.StandardWebSocketSession std) {
                std.getNativeSession().setMaxIdleTimeout(WebSocketConfig.MAX_SESSION_IDLE_MILLIS);
            }
        } catch (Exception e) {
            log.warn("Failed to set idle timeout on session {}: {}", rawSession.getId(), e.getMessage());
        }

        DatapointListenSession listen = new DatapointListenSession(
                safeSession, consumer, jsonMapper, tenantId, permissions, initialInterest);
        sessions.put(rawSession.getId(), listen);
        wsSessions.put(rawSession.getId(), safeSession);
        listen.start(receiveExecutor);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        DatapointListenSession listen = sessions.get(session.getId());
        if (listen == null) {
            log.debug("Received text on session {} but no listen session found", session.getId());
            return;
        }
        try {
            JsonNode node = jsonMapper.readTree(message.getPayload());
            String action = node.path("action").asString("set");
            List<String> ids = new ArrayList<>();
            JsonNode idsNode = node.path("externalIds");
            if (idsNode.isArray()) {
                for (JsonNode id : idsNode) ids.add(id.asString());
            }
            // Re-apply the dataset ACL on every interest change: a caller can only ever add
            // timeseries whose dataset they may read. Removals need no check.
            switch (action) {
                case "subscribe", "add" -> listen.addInterest(
                        accessAuthorizer.readableExternalIds(listen.getTenantId(), listen.getPermissions(), ids));
                case "unsubscribe", "remove" -> listen.removeInterest(ids);
                default -> listen.setInterest(
                        accessAuthorizer.readableExternalIds(listen.getTenantId(), listen.getPermissions(), ids));
            }
        } catch (Exception e) {
            log.error("Failed to parse datapoint-listen message on session {}: {}", session.getId(), e.getMessage());
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        wsSessions.remove(session.getId());
        ConnectionOwner owner = owners.remove(session.getId());
        if (owner != null) {
            connectionLimiter.release(owner.tenantId(), owner.subject(), session.getId());
        }
        DatapointListenSession listen = sessions.remove(session.getId());
        if (listen != null) listen.stop();
        log.info("Datapoint listen WS closed: session={} status={}", session.getId(), status);
    }

    @PreDestroy
    public void shutdown() {
        pingScheduler.shutdownNow();
        wsSessions.clear();
        sessions.values().forEach(DatapointListenSession::stop);
        sessions.clear();
        receiveExecutor.shutdownNow();
        try {
            receiveExecutor.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * One non-durable, exclusive consumer per connection reading the all-datapoints topic from
     * {@code latest}. Non-durable + a unique subscription name means each browser tab gets an
     * independent live tail and the broker keeps no cursor once the consumer closes.
     */
    private Consumer<DataWrapperBin> buildConsumer() throws PulsarClientException {
        String name = "ws-dp-listen-" + Long.toHexString(System.nanoTime());
        return pulsarClient.newConsumer(Schema.AVRO(DataWrapperBin.class))
                .topic(topicNames.getAllDatapointsTopicName())
                .subscriptionName(name)
                .consumerName(name)
                .subscriptionType(SubscriptionType.Exclusive)
                .subscriptionMode(SubscriptionMode.NonDurable)
                .subscriptionInitialPosition(SubscriptionInitialPosition.Latest)
                .autoUpdatePartitionsInterval(30, TimeUnit.SECONDS)
                .batchReceivePolicy(BatchReceivePolicy.builder()
                        .maxNumMessages(BATCH_MAX_MESSAGES)
                        .maxNumBytes(BATCH_MAX_BYTES)
                        .timeout(BATCH_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                        .build())
                .subscribe();
    }

    /**
     * One frame explaining the refusal, so a client sees a reason rather than a bare close code.
     * Shaped like {@link SubscriptionWebSocketHandler}'s error frames, so a client that speaks to
     * both sockets needs one parser rather than two.
     */
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
                log.warn("Ping failed for datapoint-listen session {} ({}); leaving container to clean up",
                        entry.getKey(), e.getMessage());
            }
        }
    }

    /**
     * Extract the tenant id from the JWT's {@code organization} claim — same shape as
     * {@code SecurityConfig.OrganizationValidator}.
     */
    private static String extractTenantId(Jwt jwt) {
        Map<String, Object> orgs = jwt.getClaimAsMap("organization");
        if (orgs == null || orgs.isEmpty()) return null;
        Object first = orgs.values().iterator().next();
        if (first instanceof Map<?, ?> details && details.get("id") != null) {
            return details.get("id").toString();
        }
        return null;
    }

    private static List<String> parseExternalIds(String csv) {
        if (csv == null || csv.isBlank()) return List.of();
        return Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    /** Read a single URL-decoded query-parameter value from the handshake URI. */
    private static String queryParam(URI uri, String name) {
        String raw = uri.getRawQuery();
        if (raw == null || raw.isEmpty()) return null;
        for (String pair : raw.split("&")) {
            int eq = pair.indexOf('=');
            String key = eq >= 0 ? pair.substring(0, eq) : pair;
            if (key.equals(name)) {
                String value = eq >= 0 ? pair.substring(eq + 1) : "";
                return URLDecoder.decode(value, StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    private static void closeQuietly(WebSocketSession session, CloseStatus status) {
        try {
            session.close(status);
        } catch (Exception e) {
            log.warn("Failed to close datapoint-listen WS session cleanly: {}", e.getMessage());
        }
    }
}
