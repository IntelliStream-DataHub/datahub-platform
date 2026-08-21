// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.config;

import ai.intellistream.datahub.pulsar.TopicNames;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.apache.pulsar.client.api.Producer;
import org.apache.pulsar.client.api.ProducerAccessMode;
import org.apache.pulsar.client.api.PulsarClient;
import org.apache.pulsar.client.api.PulsarClientException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Fails startup loudly when another instance of the same service is already running with the same
 * instance id, instead of letting two instances silently collide on their Pulsar producer/consumer
 * names (which breaks fan-out).
 *
 * <p>Uses the broker as the arbiter: it holds a uniquely-named producer — {@code <service>-<id>} —
 * on the coordination topic {@link TopicNames#getInstanceLockTopicName()}. Pulsar allows only one
 * producer per name per topic, so a second instance with the same {@code (service, id)} gets a
 * {@link PulsarClientException.ProducerBusyException} on create, which we turn into a clear
 * fail-fast. The lock is scoped per service (via {@code spring.application.name}) so a co-located
 * api and consumer that share the derived {@code hostname-numa} id do not falsely collide. The
 * producer holds nothing but its name; it is released on shutdown and the broker frees it if the
 * instance dies.
 *
 * <p><b>Why the lock lives in Pulsar and not another data store:</b> every other store here
 * (Postgres, ClickHouse, Neo4j, Valkey, KVRocks) is routed per-tenant via {@code TenantContext}, so
 * none is shared globally by the api and consumer; Vault is global but is a secrets store, not a
 * lock primitive. Pulsar's internal tenant is the one global coordination point these services
 * already depend on. It also fits better than the conventional alternatives:
 * <ul>
 *   <li>a Postgres advisory lock is session-scoped and would be broken by the transaction-pooling
 *       pgbouncer in front of Postgres (the session isn't pinned to one backend);</li>
 *   <li>a Redis/Valkey {@code SET NX PX} lock is TTL-based — it would need a renewal heartbeat to
 *       hold it for the process lifetime, plus a global (non-tenant) instance that doesn't exist
 *       here.</li>
 * </ul>
 * The Pulsar producer lock is instead connection-liveness-based (like a ZooKeeper ephemeral node):
 * no TTL to tune, and the broker frees it automatically when the instance dies. It also co-locates
 * the lock with the exact resource it guards — Pulsar producer names — so lock availability tracks
 * the availability of the thing it protects, rather than a separate store granting the lock while
 * Pulsar is degraded and giving a false all-clear.
 */
@Component
@Slf4j
public class InstanceLock {

    private final PulsarClient pulsarClient;
    private final TopicNames topicNames;
    private final AppInstanceId appInstanceId;
    private final String serviceName;
    private Producer<byte[]> lock;

    public InstanceLock(PulsarClient pulsarClient,
                        TopicNames topicNames,
                        AppInstanceId appInstanceId,
                        @Value("${spring.application.name:datahub}") String serviceName) {
        this.pulsarClient = pulsarClient;
        this.topicNames = topicNames;
        this.appInstanceId = appInstanceId;
        this.serviceName = serviceName;
    }

    @PostConstruct
    public void acquire() {
        String lockName = serviceName + "-" + appInstanceId.get();
        String topic = topicNames.getInstanceLockTopicName();
        try {
            lock = pulsarClient.newProducer()
                    .topic(topic)
                    .producerName(lockName)
                    .accessMode(ProducerAccessMode.Shared)
                    .create();
            log.info("Acquired instance lock '{}' on {}", lockName, topic);
        } catch (PulsarClientException e) {
            String message = e.getMessage() == null ? "" : e.getMessage();
            if (e instanceof PulsarClientException.ProducerBusyException || message.contains("already connected")) {
                throw new IllegalStateException(
                        "Instance id '" + appInstanceId.get() + "' is already in use by another running "
                                + serviceName + " instance (lock '" + lockName + "' on " + topic + "). "
                                + "Each instance needs a unique id: set the 'app.id' property to a unique value, "
                                + "or run at most one " + serviceName + " per (host, NUMA node). Refusing to start.", e);
            }
            throw new IllegalStateException(
                    "Failed to acquire instance lock '" + lockName + "' on " + topic + ": " + message, e);
        }
    }

    /** This instance's resolved id. */
    public String id() {
        return appInstanceId.get();
    }

    @PreDestroy
    public void release() {
        if (lock == null) return;
        try {
            lock.close();
        } catch (Exception e) {
            log.warn("Failed to release instance lock: {}", e.getMessage());
        }
    }
}
