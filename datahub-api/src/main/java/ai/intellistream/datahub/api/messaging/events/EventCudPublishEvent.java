// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.messaging.events;

import ai.intellistream.datahub.pulsar.EventCudMessage;

/**
 * Signals that an {@link EventCudMessage} should be published to Pulsar once the enclosing
 * JPA/KVRocks transaction commits. Consumed by {@code AfterCommitMessagePublisher}.
 */
public record EventCudPublishEvent(EventCudMessage message) {
}
