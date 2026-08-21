// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.websocket;

import ai.intellistream.datahub.api.datasecurity.DatasetPermissions;
import ai.intellistream.datahub.api.responses.DataCollectionString;
import ai.intellistream.datahub.api.responses.DataWrapperBin;
import ai.intellistream.datahub.api.responses.DataWrapperMessage;
import ai.intellistream.datahub.api.responses.DatapointString;
import ai.intellistream.datahub.clickhouse.DatapointBinaryConverter;
import ai.intellistream.datahub.pulsar.EventAction;
import ai.intellistream.datahub.pulsar.EventObject;
import lombok.extern.slf4j.Slf4j;
import org.apache.pulsar.client.api.*;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Per-connection state for the datapoint listen WebSocket. Owns one non-durable Pulsar consumer
 * that tails the global all-datapoints topic and the background receive loop that filters each
 * batch down to the timeseries this client asked for before forwarding it.
 * <p>
 * Unlike {@link SubscriptionListenSession}, there is no per-message ack protocol with the client:
 * this is a live tail, so dropped frames are acceptable and the server acks Pulsar as soon as it
 * has forwarded (or discarded) a batch. The only client → server message is the interest update
 * (which timeseries externalIds to stream).
 */
@Slf4j
class DatapointListenSession {

    private final WebSocketSession session;
    private final Consumer<DataWrapperBin> consumer;
    private final JsonMapper jsonMapper;
    private final String tenantId;
    // The caller's dataset permissions, captured at connect. The handler uses these to authorise
    // every interest change so a client can only ever tail timeseries whose dataset it may read.
    private final DatasetPermissions permissions;

    // The set of timeseries externalIds this client currently wants. Empty → forward nothing.
    private final Set<String> interest = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile Future<?> receiveTask;

    DatapointListenSession(WebSocketSession session,
                           Consumer<DataWrapperBin> consumer,
                           JsonMapper jsonMapper,
                           String tenantId,
                           DatasetPermissions permissions,
                           Collection<String> initialInterest) {
        this.session = session;
        this.consumer = consumer;
        this.jsonMapper = jsonMapper;
        this.tenantId = tenantId;
        this.permissions = permissions;
        if (initialInterest != null) interest.addAll(initialInterest);
    }

    String getTenantId() {
        return tenantId;
    }

    DatasetPermissions getPermissions() {
        return permissions;
    }

    void start(ExecutorService executor) {
        if (!running.compareAndSet(false, true)) return;
        receiveTask = executor.submit(this::receiveLoop);
    }

    /**
     * Stop the receive loop and close the Pulsar consumer. The subscription is non-durable, so
     * closing the consumer leaves nothing behind on the broker. Idempotent.
     */
    void stop() {
        if (!running.compareAndSet(true, false)) return;
        try {
            consumer.close();
        } catch (PulsarClientException e) {
            log.warn("Failed to close datapoint-listen Pulsar consumer for tenant {}: {}", tenantId, e.getMessage());
        }
        if (receiveTask != null) receiveTask.cancel(true);
    }

    /** Replace the whole interest set (a "set" action, or the default for an interest message). */
    void setInterest(Collection<String> externalIds) {
        interest.clear();
        if (externalIds != null) interest.addAll(externalIds);
    }

    void addInterest(Collection<String> externalIds) {
        if (externalIds != null) interest.addAll(externalIds);
    }

    void removeInterest(Collection<String> externalIds) {
        if (externalIds != null) interest.removeAll(externalIds);
    }

    private void receiveLoop() {
        Thread.currentThread().setName("datapoint-listen-" + tenantId);
        log.info("Starting datapoint listen loop for tenant {}", tenantId);
        try {
            while (running.get() && session.isOpen()) {
                Messages<DataWrapperBin> batch;
                try {
                    batch = consumer.batchReceive();
                } catch (PulsarClientException.AlreadyClosedException closed) {
                    log.debug("Datapoint-listen consumer for tenant {} already closed, exiting loop", tenantId);
                    return;
                } catch (PulsarClientException e) {
                    log.error("Pulsar receive failed for datapoint-listen (tenant {}): {}", tenantId, e.getMessage());
                    return;
                }
                if (batch == null || batch.size() == 0) continue;
                forward(batch);
            }
        } catch (Exception e) {
            log.error("Unexpected error in datapoint-listen loop for tenant {}: {}", tenantId, e.getMessage(), e);
        } finally {
            log.info("Exiting datapoint listen loop for tenant {}", tenantId);
        }
    }

    /**
     * Filter a batch from the (cross-tenant) all-datapoints topic down to this client's tenant and
     * interest set, then forward the matched points as one frame. Every message is acked afterwards
     * regardless of whether it matched — we have seen and handled it.
     */
    private void forward(Messages<DataWrapperBin> batch) {
        List<Map<String, Object>> points = new ArrayList<>();
        boolean haveInterest = !interest.isEmpty();
        for (Message<DataWrapperBin> msg : batch) {
            try {
                DataWrapperBin bin = msg.getValue();
                // Cheap rejects first: other tenants' messages and non-create datapoint events are
                // skipped without paying for a binary→string decode. Tenant isolation is enforced
                // here — a client only ever sees its own tenant's datapoints.
                if (haveInterest
                        && tenantId.equals(bin.getTenantId())
                        && bin.getEventObject() == EventObject.DATAPOINTS
                        && bin.getEventAction() == EventAction.CREATE) {
                    DataWrapperMessage decoded = DatapointBinaryConverter.toStringMessage(bin);
                    collectMatching(decoded, points);
                }
            } catch (Exception e) {
                log.warn("Skipping malformed all-datapoints message for tenant {}: {}", tenantId, e.getMessage());
            } finally {
                consumer.acknowledgeAsync(msg);
            }
        }
        if (!points.isEmpty()) send(points);
    }

    private void collectMatching(DataWrapperMessage decoded, List<Map<String, Object>> out) {
        if (decoded.getItems() == null) return;
        for (DataCollectionString item : decoded.getItems()) {
            String externalId = item.getExternalId();
            if (externalId == null || !interest.contains(externalId) || item.getDatapoints() == null) continue;
            for (DatapointString dp : item.getDatapoints()) {
                Map<String, Object> point = new LinkedHashMap<>();
                point.put("externalId", externalId);
                point.put("valueType", item.getValueType());
                point.put("timestamp", dp.getTimestamp());
                point.put("value", dp.getValue());
                out.add(point);
            }
        }
    }

    private void send(List<Map<String, Object>> points) {
        try {
            String json = jsonMapper.writeValueAsString(Map.of("datapoints", points));
            session.sendMessage(new TextMessage(json));
        } catch (Exception e) {
            log.error("Failed to send datapoint frame to tenant {}: {}", tenantId, e.getMessage());
            try {
                session.close(CloseStatus.SERVER_ERROR);
            } catch (Exception ignored) {
                // nothing to do
            }
        }
    }
}
