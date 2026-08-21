// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.messaging.events;

import ai.intellistream.datahub.pulsar.SubscriptionNotifyMessage;

/**
 * Signals that a {@link SubscriptionNotifyMessage} should be published to Pulsar once the
 * enclosing transaction commits, so the consumer cache is only notified of subscriptions
 * that actually persisted.
 */
public record SubscriptionNotifyPublishEvent(SubscriptionNotifyMessage message) {
}
