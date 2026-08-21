// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.sdk.subscriptions;

/**
 * A server-side error notice for a single subscription, delivered over the WebSocket as
 * {@code {"error": true, "subscriptionExternalId": "<id>", "reason": "<reason>"}}. The connection is
 * <em>not</em> torn down — one requested subscription simply could not be attached — so the client
 * keeps receiving data for the subscriptions that did attach.
 *
 * <p>Common reasons: {@code "not-found"} (no such subscription for the tenant), {@code "forbidden"}
 * (the caller lacks read access to the subscription's dataset), {@code "subscribe-failed"}. Drain
 * these with {@link SubscriptionListener#pollError} so a refused subscription surfaces instead of
 * looking like an indefinitely silent stream.
 */
public record SubscriptionError(String subscriptionExternalId, String reason) {
}
