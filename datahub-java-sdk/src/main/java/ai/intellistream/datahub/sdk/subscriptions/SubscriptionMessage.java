// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.sdk.subscriptions;

import ai.intellistream.datahub.api.responses.DataWrapperMessage;

/**
 * One message delivered over a subscription. {@code messageId} is the opaque id the client echoes
 * back via {@link SubscriptionListener#ack}/{@link SubscriptionListener#nack}; {@code payload} is the
 * fan-out event (datapoints plus the event action/object).
 */
public record SubscriptionMessage(String subscriptionExternalId, String messageId, DataWrapperMessage payload) {
}
