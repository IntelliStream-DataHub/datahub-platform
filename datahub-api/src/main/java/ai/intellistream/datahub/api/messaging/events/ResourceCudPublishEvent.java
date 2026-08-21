// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.messaging.events;

import ai.intellistream.datahub.pulsar.ResourceCudMessage;

/**
 * Signals that a {@link ResourceCudMessage} should be published to Pulsar once the enclosing
 * JPA transaction commits. Fired by services inside their {@code @Transactional} methods and
 * consumed by {@code AfterCommitMessagePublisher} in a {@code AFTER_COMMIT} listener, so a
 * rollback never leaks a message the consumers would act on.
 */
public record ResourceCudPublishEvent(ResourceCudMessage message) {
}
