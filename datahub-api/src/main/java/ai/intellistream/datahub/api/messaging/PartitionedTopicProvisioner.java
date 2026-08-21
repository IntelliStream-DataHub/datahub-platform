// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.messaging;

import lombok.extern.slf4j.Slf4j;
import org.apache.pulsar.client.admin.PulsarAdmin;
import org.apache.pulsar.client.admin.PulsarAdminException;
import org.springframework.stereotype.Component;

/**
 * Idempotently provisions a topic as a <b>partitioned</b> topic, to be called before any
 * producer connects. A non-partitioned topic is owned by exactly one broker, which caps
 * throughput regardless of cluster size; partitioning spreads it across brokers.
 *
 * <p>If the topic already exists as a NON-partitioned topic it is deleted and recreated as
 * partitioned — a one-time migration on the next startup that <b>discards any messages
 * currently on the non-partitioned topic</b>. Once partitioned, the call is a no-op. Partition
 * count is increase-only in Pulsar (it can be raised later but never lowered, and raising it
 * rehashes keys), so start with headroom.
 *
 * <p>Never throws: on any admin failure the caller's producer still attempts to come up, so a
 * transient Pulsar-admin hiccup doesn't block startup.
 */
@Slf4j
@Component
public class PartitionedTopicProvisioner {

    private final PulsarAdmin pulsarAdmin;

    public PartitionedTopicProvisioner(PulsarAdmin pulsarAdmin) {
        this.pulsarAdmin = pulsarAdmin;
    }

    /**
     * Ensure {@code topicPath} exists as a partitioned topic with {@code partitions} partitions.
     */
    public void ensurePartitioned(String topicPath, int partitions) {
        try {
            int existing = pulsarAdmin.topics().getPartitionedTopicMetadata(topicPath).partitions;
            if (existing > 0) {
                log.info("Topic {} already partitioned ({} partitions).", topicPath, existing);
                return;
            }
            // partitions == 0: the topic is either missing or exists as a NON-partitioned topic.
            // A non-partitioned topic is pinned to a single broker, so recreate it as partitioned.
            // force=true disconnects any producers/consumers still attached to the old topic.
            if (nonPartitionedTopicExists(topicPath)) {
                log.warn("Topic {} exists as non-partitioned; deleting and recreating as a {}-partition "
                        + "topic. Messages currently on it are discarded.", topicPath, partitions);
                pulsarAdmin.topics().delete(topicPath, true);
            }
        } catch (PulsarAdminException.NotFoundException notFound) {
            // Topic/namespace not found — fall through to create.
        } catch (PulsarAdminException e) {
            log.error("Could not inspect topic {} before partitioned creation: {}", topicPath, e.getMessage(), e);
            return;
        }
        try {
            pulsarAdmin.topics().createPartitionedTopic(topicPath, partitions);
            log.info("Created partitioned topic {} with {} partitions.", topicPath, partitions);
        } catch (PulsarAdminException.ConflictException alreadyExists) {
            // Another instance created it (or it already exists) between our check and create.
            log.info("Topic {} already exists — adopting.", topicPath);
        } catch (PulsarAdminException.NotFoundException notFound) {
            // The namespace (or its Pulsar tenant) doesn't exist, so the topic can't be auto-created.
            // Print the exact runbook to provision it by hand, then restart this service.
            String namespace = namespaceOf(topicPath);
            String pulsarTenant = namespace.substring(0, namespace.indexOf('/'));
            log.error("Cannot create partitioned topic {}: namespace not found ({}). Create it "
                    + "manually with the Pulsar admin CLI, then restart this service:\n"
                    + "  pulsar-admin tenants create {}            # only if the tenant does not exist yet\n"
                    + "  pulsar-admin namespaces create {}\n"
                    + "  pulsar-admin topics create-partitioned-topic {} --partitions {}",
                    topicPath, notFound.getMessage(), pulsarTenant, namespace, topicPath, partitions);
        } catch (PulsarAdminException e) {
            log.error("Failed to create partitioned topic {}: {}", topicPath, e.getMessage(), e);
        }
    }

    /** persistent://&lt;tenant&gt;/&lt;namespace&gt;/&lt;topic&gt; → &lt;tenant&gt;/&lt;namespace&gt; */
    private static String namespaceOf(String topicPath) {
        String afterScheme = topicPath.substring(topicPath.indexOf("://") + 3);
        return afterScheme.substring(0, afterScheme.lastIndexOf('/'));
    }

    /**
     * True if a non-partitioned topic with this exact name currently exists in the namespace.
     * Only meaningful once the partitioned metadata has reported zero partitions; otherwise the
     * topic is partitioned and its individual partitions — not this base name — appear in the list.
     */
    private boolean nonPartitionedTopicExists(String topicPath) throws PulsarAdminException {
        // topicPath is always persistent://<tenant>/<namespace>/<topic>
        return pulsarAdmin.topics().getList(namespaceOf(topicPath)).contains(topicPath);
    }
}
