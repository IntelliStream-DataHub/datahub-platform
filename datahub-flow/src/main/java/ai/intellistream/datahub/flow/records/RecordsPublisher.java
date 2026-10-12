// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.flow.records;

import ai.intellistream.datahub.tenant.Tenant;
import ai.intellistream.datahub.tenant.TenantConfigService;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.apache.pulsar.client.api.BatcherBuilder;
import org.apache.pulsar.client.api.CompressionType;
import org.apache.pulsar.client.api.Producer;
import org.apache.pulsar.client.api.PulsarClient;
import org.apache.pulsar.client.api.Schema;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Publishes runs' records to their tenant's topic, {@code persistent://<pulsar-tenant>/flow/records}.
 *
 * <p>One producer per tenant, made when that tenant's first run publishes and kept. The map holds a
 * future reserved with {@code putIfAbsent}, so concurrent first runs share one creation, and a
 * failed creation is dropped so the next run tries again.
 *
 * <p>Every message is keyed by its run id, and its ordering key is the same id: the consumers read
 * the topic Key_Shared, which keeps one key's messages in order on one consumer, and key-based
 * batching groups a producer batch by ordering key — a batch mixing runs would be delivered whole to
 * whichever consumer owns its first key. No producer name is given: Pulsar generates a unique one,
 * and two instances must never collide on a name.
 */
@Slf4j
@Component
public class RecordsPublisher implements RunRecords.Opener {

    private final PulsarClient pulsar;
    private final TenantConfigService tenants;
    private final long cap;
    private final Map<String, CompletableFuture<Producer<FlowRecordBatch>>> producers =
            new ConcurrentHashMap<>();

    public RecordsPublisher(PulsarClient pulsar, TenantConfigService tenants,
                            @Value("${datahub.flow.records.max-per-run:100000}") long cap) {
        this.pulsar = pulsar;
        this.tenants = tenants;
        this.cap = cap;
    }

    @Override
    public RunRecords open(String tenantId, UUID runId, int attempt, String flowExternalId, int flowVersion) {
        return new RunRecords(runId, attempt, flowExternalId, flowVersion, cap, Clock.systemUTC(),
                batch -> producer(tenantId).thenCompose(p -> p.newMessage()
                        .key(batch.getRunId())
                        .orderingKey(batch.getRunId().getBytes(StandardCharsets.UTF_8))
                        .value(batch)
                        .sendAsync()));
    }

    private CompletableFuture<Producer<FlowRecordBatch>> producer(String tenantId) {
        CompletableFuture<Producer<FlowRecordBatch>> existing = producers.get(tenantId);
        if (existing != null && !existing.isCompletedExceptionally()) {
            return existing;
        }
        CompletableFuture<Producer<FlowRecordBatch>> mine = new CompletableFuture<>();
        if (existing == null ? producers.putIfAbsent(tenantId, mine) != null
                : !producers.replace(tenantId, existing, mine)) {
            return producers.get(tenantId);
        }
        try {
            pulsar.newProducer(Schema.AVRO(FlowRecordBatch.class))
                    .topic(RecordsTopicProvisioner.topic(pulsarTenant(tenantId)))
                    .batcherBuilder(BatcherBuilder.KEY_BASED)
                    .compressionType(CompressionType.ZSTD)
                    .sendTimeout(30, TimeUnit.SECONDS)
                    .blockIfQueueFull(true)
                    .createAsync()
                    .whenComplete((p, e) -> {
                        if (e != null) {
                            log.warn("Could not create the records producer for tenant {}: {}",
                                    tenantId, e.getMessage());
                            mine.completeExceptionally(e);
                        } else {
                            mine.complete(p);
                        }
                    });
        } catch (RuntimeException e) {
            mine.completeExceptionally(e);
        }
        return mine;
    }

    private String pulsarTenant(String tenantId) {
        Tenant tenant = tenants.getConfig(tenantId);
        if (tenant == null || tenant.getPulsarTenant() == null || tenant.getPulsarTenant().getTenant() == null) {
            throw new IllegalStateException("Tenant " + tenantId + " has no pulsar.tenant configured.");
        }
        return tenant.getPulsarTenant().getTenant();
    }

    @PreDestroy
    void close() {
        producers.values().forEach(f -> f.thenAccept(p -> {
            try {
                p.flush();
                p.close();
            } catch (Exception e) {
                log.debug("Closing a records producer: {}", e.getMessage());
            }
        }));
        producers.clear();
    }
}
