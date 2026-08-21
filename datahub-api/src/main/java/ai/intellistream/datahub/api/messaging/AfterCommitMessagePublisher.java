// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.messaging;

import ai.intellistream.datahub.api.messaging.events.DatapointCudPublishEvent;
import ai.intellistream.datahub.api.messaging.events.EventCudPublishEvent;
import ai.intellistream.datahub.api.messaging.events.ResourceCudPublishEvent;
import ai.intellistream.datahub.api.messaging.events.SubscriptionNotifyPublishEvent;
import ai.intellistream.datahub.api.responses.DataWrapperBin;
import ai.intellistream.datahub.api.services.LiveIngestCounter;
import ai.intellistream.datahub.pulsar.EventAction;
import ai.intellistream.datahub.pulsar.EventCudMessage;
import ai.intellistream.datahub.pulsar.ResourceCudMessage;
import ai.intellistream.datahub.pulsar.SubscriptionNotifyMessage;
import lombok.extern.slf4j.Slf4j;
import org.apache.pulsar.client.api.Producer;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Forwards domain events published by services inside {@code @Transactional} methods to the
 * corresponding Pulsar producer — but only after the JPA transaction has committed. This
 * closes the dual-write window where a rollback could otherwise leave Pulsar consumers (Neo4j,
 * ClickHouse, KVRocks) acting on state that no longer exists in Postgres.
 *
 * <p>Residual gap: a JVM crash between commit and the async send means the consumer never
 * receives the message. That window is small and is intentionally left for a follow-up outbox
 * table; for now we log loudly so operators can reconcile.
 */
@Component
@Slf4j
public class AfterCommitMessagePublisher {

    private final Producer<ResourceCudMessage> resourceMessageProducer;
    private final Producer<EventCudMessage> eventMessageProducer;
    private final Producer<SubscriptionNotifyMessage> subscriptionNotifyProducer;
    private final Producer<DataWrapperBin> allDatapointProducer;
    private final LiveIngestCounter eventIngestCounter;

    public AfterCommitMessagePublisher(
            @Qualifier("resourceMessageProducer") Producer<ResourceCudMessage> resourceMessageProducer,
            @Qualifier("eventMessageProducer") Producer<EventCudMessage> eventMessageProducer,
            @Qualifier("subscriptionNotifyProducer") Producer<SubscriptionNotifyMessage> subscriptionNotifyProducer,
            @Qualifier("allDatapointProducer") Producer<DataWrapperBin> allDatapointProducer,
            @Qualifier("eventIngestCounter") LiveIngestCounter eventIngestCounter) {
        this.resourceMessageProducer = resourceMessageProducer;
        this.eventMessageProducer = eventMessageProducer;
        this.subscriptionNotifyProducer = subscriptionNotifyProducer;
        this.allDatapointProducer = allDatapointProducer;
        this.eventIngestCounter = eventIngestCounter;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onResourceCud(ResourceCudPublishEvent event) {
        ResourceCudMessage msg = event.message();
        // Keyed by tenantId so same-tenant resource CUD stays ordered if the resource
        // topic is later partitioned, or if a Key_Shared consumer subscription is added.
        // Today the Neo4j/timeseries CUD consumers are Exclusive + single-threaded, so the
        // key is redundant — it's here to keep the contract uniform across producers.
        resourceMessageProducer.newMessage()
                .key(msg.getTenantId())
                .value(msg)
                .sendAsync()
                .whenComplete((id, err) -> {
                    if (err != null) {
                        log.error("Post-commit publish of ResourceCudMessage failed (tenant={}, action={}, object={}): {}",
                                msg.getTenantId(), msg.getEventAction(), msg.getEventObject(),
                                err.getMessage(), err);
                    }
                });
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onDatapointCud(DatapointCudPublishEvent event) {
        DataWrapperBin msg = event.message();
        // Keyed by tenantId purely for the uniform contract; BatchedDatapointsListener uses a
        // Shared subscription, so the all-datapoints topic carries no ordering guarantee either
        // way. A purge published here can therefore be applied while an insert for the same
        // timeseries is still in flight, leaving a few residual rows. Harmless in practice: the
        // node row is already committed as deleted, so the api rejects any *new* write for it,
        // and residual rows are unreachable (every read path resolves the node first).
        allDatapointProducer.newMessage()
                .key(msg.getTenantId())
                .value(msg)
                .sendAsync()
                .whenComplete((id, err) -> {
                    if (err != null) {
                        log.error("Post-commit publish of datapoint {} failed (tenant={}, items={}): {}",
                                msg.getEventAction(), msg.getTenantId(),
                                msg.getItems() == null ? 0 : msg.getItems().size(),
                                err.getMessage(), err);
                    }
                });
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onEventCud(EventCudPublishEvent event) {
        EventCudMessage msg = event.message();
        // Keyed by tenantId so the Key_Shared subscription on BatchedEventsListener routes
        // all of a tenant's CUD events to the same in-JVM worker — which serializes them,
        // preserving CREATE→UPDATE→DELETE order per event within that tenant. Events are
        // tenant-scoped, so per-tenant ordering implies per-event ordering.
        eventMessageProducer.newMessage()
                .key(msg.getTenantId())
                .value(msg)
                .sendAsync()
                .whenComplete((id, err) -> {
                    if (err != null) {
                        log.error("Post-commit publish of EventCudMessage failed (tenant={}, action={}, object={}): {}",
                                msg.getTenantId(), msg.getEventAction(), msg.getEventObject(),
                                err.getMessage(), err);
                        return;
                    }
                    // Only CREATE grows the events table; UPDATE re-inserts the same id (ReplacingMergeTree
                    // dedups it away at merge time) and DELETE doesn't apply here at all.
                    if (msg.getEventAction() == EventAction.CREATE) {
                        eventIngestCounter.recordIngested(msg.getTenantId(), msg.getEvents().size());
                    }
                });
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onSubscriptionNotify(SubscriptionNotifyPublishEvent event) {
        SubscriptionNotifyMessage msg = event.message();
        // Keyed by tenantId so create/delete notifies for the same tenant's subscription
        // cache stay in order even if the notify topic is partitioned or the consumer is
        // later scaled to Key_Shared. Current SubscriptionNotifyListener is single-consumer.
        subscriptionNotifyProducer.newMessage()
                .key(msg.getTenantId())
                .value(msg)
                .sendAsync()
                .whenComplete((id, err) -> {
                    if (err != null) {
                        log.error("Post-commit publish of SubscriptionNotifyMessage failed (tenant={}, externalId={}, action={}): {}",
                                msg.getTenantId(), msg.getSubscriptionExternalId(), msg.getEventAction(),
                                err.getMessage(), err);
                    }
                });
    }
}
