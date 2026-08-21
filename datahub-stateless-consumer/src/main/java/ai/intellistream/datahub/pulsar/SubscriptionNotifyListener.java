// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.pulsar;

import ai.intellistream.datahub.config.AppInstanceId;
import ai.intellistream.datahub.subscription.SubscriptionCache;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.apache.pulsar.client.api.Consumer;
import org.apache.pulsar.client.api.Message;
import org.apache.pulsar.client.api.PulsarClient;
import org.apache.pulsar.client.api.PulsarClientException;
import org.apache.pulsar.client.api.Schema;
import org.apache.pulsar.client.api.SubscriptionInitialPosition;
import org.apache.pulsar.client.api.SubscriptionMode;
import org.apache.pulsar.client.api.SubscriptionType;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Keeps {@link SubscriptionCache} in sync with subscriptions created/deleted in datahub-api at
 * runtime, by consuming the {@code subscriptions/notify} topic.
 *
 * <p>That topic is a cache-invalidation <b>broadcast</b>: every consumer instance holds its own
 * {@link SubscriptionCache} and must see <em>every</em> notify. So each instance subscribes with its
 * own <b>unique, non-durable</b> subscription (named by {@link AppInstanceId}) rather than a single
 * shared one — a shared Exclusive name lets only one instance attach and leaves the rest silently
 * running with a frozen cache, and non-durable means nothing is left on the broker when an instance
 * dies. The per-instance id is already made unique at startup by {@code InstanceLock}.
 *
 * <p>Startup ordering matters: this listener <b>subscribes first</b> (pinning the stream position at
 * {@code Latest}) and only then loads the Postgres snapshot ({@link SubscriptionCache#loadAll()}). A
 * subscription created between a snapshot-then-subscribe would fall into the gap between them. Notify
 * deltas that arrive while the snapshot loads are held in {@link #onMessage} on {@link #snapshotLoaded}
 * until it is applied, so a DELETE can never be processed before the snapshot's matching ADD (which
 * would leave a permanent stale entry).
 */
@Service
@Slf4j
@DependsOn({"instanceLock"})
public class SubscriptionNotifyListener {

    private final PulsarClient pulsarClient;
    private final TopicNames topicNames;
    private final SubscriptionCache subscriptionCache;
    private final AppInstanceId appInstanceId;
    private Consumer<SubscriptionNotifyMessage> consumer;

    // Held closed until the Postgres snapshot is loaded; onMessage waits on it so notify deltas are
    // applied only on top of the snapshot, never before it.
    private final CountDownLatch snapshotLoaded = new CountDownLatch(1);

    public SubscriptionNotifyListener(PulsarClient pulsarClient,
                                      TopicNames topicNames,
                                      SubscriptionCache subscriptionCache,
                                      AppInstanceId appInstanceId) {
        this.pulsarClient = pulsarClient;
        this.topicNames = topicNames;
        this.subscriptionCache = subscriptionCache;
        this.appInstanceId = appInstanceId;
    }

    @PostConstruct
    public void init() {
        String subscriptionName = "subscription-notify-cache-" + appInstanceId.get();
        try {
            // Subscribe BEFORE loading the snapshot so the stream position is pinned first.
            consumer = pulsarClient.newConsumer(Schema.AVRO(SubscriptionNotifyMessage.class))
                    .subscriptionName(subscriptionName)
                    .consumerName("subscription-notify-consumer-" + appInstanceId.get())
                    .topic(topicNames.getSubscriptionNotifyTopicName())
                    .subscriptionType(SubscriptionType.Exclusive)
                    .subscriptionMode(SubscriptionMode.NonDurable)
                    .subscriptionInitialPosition(SubscriptionInitialPosition.Latest)
                    .ackTimeout(60, TimeUnit.SECONDS)
                    .messageListener(this::onMessage)
                    .subscribe();
        } catch (PulsarClientException e) {
            // Fail fast: a consumer that can't establish its notify feed would serve stale routing
            // forever. Stop and let the orchestrator restart it rather than run half-working.
            throw new IllegalStateException(
                    "Failed to subscribe to the subscription-notify topic (subscription '"
                            + subscriptionName + "'); refusing to start.", e);
        }

        // Read the Postgres snapshot on top of the pinned position, then release onMessage.
        subscriptionCache.loadAll();
        snapshotLoaded.countDown();
        log.info("Subscription notify listener ready (instance {}, subscription {}).",
                appInstanceId.get(), subscriptionName);
    }

    private void onMessage(Consumer<SubscriptionNotifyMessage> c, Message<SubscriptionNotifyMessage> msg) {
        try {
            snapshotLoaded.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            c.negativeAcknowledge(msg);
            return;
        }
        try {
            SubscriptionNotifyMessage m = msg.getValue();
            List<Long> timeseriesIds = m.getTimeseriesIds() == null ? List.of() : m.getTimeseriesIds();
            switch (m.getEventAction()) {
                case CREATE -> {
                    for (Long tsId : timeseriesIds) {
                        subscriptionCache.add(m.getTenantId(), tsId, m.getSubscriptionExternalId());
                    }
                    log.debug("Cached subscription {} for tenant {} across {} timeseries",
                            m.getSubscriptionExternalId(), m.getTenantId(), timeseriesIds.size());
                }
                case DELETE -> {
                    for (Long tsId : timeseriesIds) {
                        subscriptionCache.remove(m.getTenantId(), tsId, m.getSubscriptionExternalId());
                    }
                    log.debug("Evicted subscription {} for tenant {} across {} timeseries",
                            m.getSubscriptionExternalId(), m.getTenantId(), timeseriesIds.size());
                }
                default -> log.debug("Ignoring subscription notify with eventAction={}", m.getEventAction());
            }
            c.acknowledge(msg);
        } catch (Exception e) {
            log.error("Failed to apply subscription notify message: {}", e.getMessage(), e);
            c.negativeAcknowledge(msg);
        }
    }

    @PreDestroy
    public void cleanup() {
        if (consumer != null) {
            consumer.closeAsync();
        }
    }
}
