// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.flow.records;

import ai.intellistream.datahub.clickhouse.ClickHouseClientPool;
import ai.intellistream.datahub.tenant.Tenant;
import ai.intellistream.datahub.tenant.TenantConfigService;
import ai.intellistream.datahub.tenant.TenantRemovedEvent;
import com.clickhouse.client.api.Client;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.apache.pulsar.client.api.BatchReceivePolicy;
import org.apache.pulsar.client.api.Consumer;
import org.apache.pulsar.client.api.Message;
import org.apache.pulsar.client.api.Messages;
import org.apache.pulsar.client.api.PulsarClient;
import org.apache.pulsar.client.api.PulsarClientException;
import org.apache.pulsar.client.api.Schema;
import org.apache.pulsar.client.api.SubscriptionInitialPosition;
import org.apache.pulsar.client.api.SubscriptionType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * Moves records from each tenant's records topic into that tenant's ClickHouse
 * ({@code flow_run_event}). Every instance runs one consumer per provisioned tenant topic, on the
 * shared subscription {@value RecordsTopicProvisioner#SUBSCRIPTION}, Key_Shared by run id, so one
 * run's batches are handled by one consumer, in order.
 *
 * <p><b>One thread per tenant.</b> Each consumer has a thread of its own, so a tenant whose
 * ClickHouse is down stalls that tenant's records and nobody else's.
 *
 * <p><b>Acknowledged after the insert.</b> A batch is acknowledged only once ClickHouse has stored
 * it ({@link RecordsTable#insert}, deduplicated on the batch's identity, so a redelivery after a
 * crash stores nothing twice). A failed insert is negatively acknowledged and comes back after the
 * redelivery delay, for as long as it takes: there is no dead-letter topic, since a record moved to
 * one is a record lost. The topic's backlog quota is what bounds the wait.
 *
 * <p><b>A message that cannot be decoded is dropped</b>, logged with its id. This differs from the
 * events consumer, which never acknowledges such a message: here the subscription's backlog would
 * then never shrink past it, and the backlog quota would eventually fail every run of the tenant
 * for one bad message. A message that cannot be read now cannot be read later either.
 */
@Slf4j
@Component
public class RecordsConsumers {

    private final PulsarClient pulsar;
    private final TenantConfigService tenants;
    private final RecordsTopicProvisioner provisioner;
    private final Function<Tenant, Client> clickhouse;
    private final Duration redeliveryDelay;
    private final Map<String, Running> running = new ConcurrentHashMap<>();
    private volatile boolean stopping;

    private record Running(Consumer<FlowRecordBatch> consumer, Thread thread) {
    }

    @Autowired
    public RecordsConsumers(PulsarClient pulsar, TenantConfigService tenants,
                            RecordsTopicProvisioner provisioner, ClickHouseClientPool clickhouse,
                            @Value("${datahub.flow.records.redelivery-delay:PT5S}") Duration redeliveryDelay) {
        this(pulsar, tenants, provisioner, clickhouse::getClient, redeliveryDelay);
    }

    RecordsConsumers(PulsarClient pulsar, TenantConfigService tenants, RecordsTopicProvisioner provisioner,
                     Function<Tenant, Client> clickhouse, Duration redeliveryDelay) {
        this.pulsar = pulsar;
        this.tenants = tenants;
        this.provisioner = provisioner;
        this.clickhouse = clickhouse;
        this.redeliveryDelay = redeliveryDelay;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void startAll() {
        startNew();
    }

    /** Starts a consumer for every tenant topic provisioned since the last pass. */
    @Scheduled(initialDelayString = "${datahub.flow.records.consumer-interval:PT30S}",
            fixedDelayString = "${datahub.flow.records.consumer-interval:PT30S}")
    public void startNew() {
        for (String tenantId : provisioner.provisioned()) {
            if (!running.containsKey(tenantId)) {
                start(tenantId);
            }
        }
    }

    @EventListener
    public void onTenantRemoved(TenantRemovedEvent event) {
        stop(event.tenantId());
    }

    void start(String tenantId) {
        Tenant tenant = tenants.getConfig(tenantId);
        if (stopping || tenant == null || tenant.getPulsarTenant() == null) {
            return;
        }
        String topic = RecordsTopicProvisioner.topic(tenant.getPulsarTenant().getTenant());
        Consumer<FlowRecordBatch> consumer;
        try {
            consumer = pulsar.newConsumer(Schema.AVRO(FlowRecordBatch.class))
                    .topic(topic)
                    .subscriptionName(RecordsTopicProvisioner.SUBSCRIPTION)
                    .subscriptionType(SubscriptionType.Key_Shared)
                    .subscriptionInitialPosition(SubscriptionInitialPosition.Earliest)
                    // Batches are already batched: a small queue keeps unprocessed ones few.
                    .receiverQueueSize(50)
                    .batchReceivePolicy(BatchReceivePolicy.builder()
                            .maxNumMessages(50)
                            .maxNumBytes(8 * 1024 * 1024)
                            .timeout(500, TimeUnit.MILLISECONDS)
                            .build())
                    .negativeAckRedeliveryDelay(redeliveryDelay.toMillis(), TimeUnit.MILLISECONDS)
                    .subscribe();
        } catch (PulsarClientException e) {
            log.warn("Could not subscribe to {} for tenant {}; retried on the next pass: {}", topic,
                    tenantId, e.getMessage());
            return;
        }
        Thread thread = Thread.ofVirtual().name("flow-records-" + tenantId)
                .unstarted(() -> consume(tenantId, consumer));
        if (running.putIfAbsent(tenantId, new Running(consumer, thread)) != null) {
            consumer.closeAsync();
            return;
        }
        thread.start();
        log.info("Consuming records of tenant {} from {}", tenantId, topic);
    }

    private void consume(String tenantId, Consumer<FlowRecordBatch> consumer) {
        int failures = 0;
        while (!stopping && running.containsKey(tenantId)) {
            Messages<FlowRecordBatch> messages;
            try {
                messages = consumer.batchReceive();
            } catch (PulsarClientException e) {
                if (stopping || !running.containsKey(tenantId)) {
                    return;
                }
                log.warn("Receiving records of tenant {} failed: {}", tenantId, e.getMessage());
                pause(Duration.ofSeconds(1));
                continue;
            }
            boolean failed = false;
            for (Message<FlowRecordBatch> message : messages) {
                failed |= !handle(tenantId, consumer, message);
            }
            if (failed) {
                // Slow down while the tenant's ClickHouse refuses: the batches come back anyway.
                failures++;
                pause(Duration.ofSeconds(Math.min(1L << Math.min(failures, 5), 30)));
            } else {
                failures = 0;
            }
        }
    }

    /** Inserts one batch and acknowledges it; false when it was negatively acknowledged. */
    boolean handle(String tenantId, Consumer<FlowRecordBatch> consumer, Message<FlowRecordBatch> message) {
        FlowRecordBatch batch;
        try {
            batch = message.getValue();
        } catch (RuntimeException e) {
            log.error("Dropping undecodable records message {} of tenant {}: {}", message.getMessageId(),
                    tenantId, e.getMessage());
            consumer.acknowledgeAsync(message);
            return true;
        }
        try {
            Tenant tenant = tenants.getConfig(tenantId);
            RecordsTable.insert(clickhouse.apply(tenant), batch, 60);
            consumer.acknowledgeAsync(message);
            return true;
        } catch (Exception e) {
            log.warn("Inserting records batch {} of tenant {} failed; it will be redelivered: {}",
                    RecordsTable.token(batch), tenantId, e.getMessage());
            consumer.negativeAcknowledge(message);
            return false;
        }
    }

    void stop(String tenantId) {
        Running r = running.remove(tenantId);
        if (r != null) {
            r.consumer().closeAsync();
            r.thread().interrupt();
        }
    }

    @PreDestroy
    void close() {
        stopping = true;
        running.keySet().forEach(this::stop);
    }

    private static void pause(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
