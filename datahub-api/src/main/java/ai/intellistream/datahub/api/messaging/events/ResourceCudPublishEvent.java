// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.messaging.events;

import ai.intellistream.datahub.pulsar.ResourceCudMessage;

/**
 * Signals that a resource change needs mirroring into the Neo4j graph. Fired by services inside
 * their {@code @Transactional} methods and consumed by {@code ResourceOutboxWriter}, which queues
 * it in the {@code resource_outbox} table before the transaction commits — so the intent to sync
 * and the change it describes become durable together, and a rollback leaves neither.
 *
 * <p>Publishing this event outside a transaction is a bug and throws: an unqueued change is one
 * the graph would never learn about.
 */
public record ResourceCudPublishEvent(ResourceCudMessage message) {
}
