// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.subscription;

/**
 * Internal-only enum mapped onto the Pulsar consumer subscription type that
 * {@code SubscriptionWebSocketHandler} uses when a WebSocket client attaches.
 *
 * <ul>
 *   <li>{@link #FAILOVER} — one active consumer at a time, others stand by. Right
 *       default for human-facing watchers (two browser tabs both see the full stream;
 *       failover on disconnect).</li>
 *   <li>{@link #KEY_SHARED} — broker partitions messages by ordering key across all
 *       attached consumers, sticky per key. System-managed function-binding subs use
 *       this so N worker processes per model split the load while preserving
 *       per-timeseries ordering.</li>
 * </ul>
 *
 * Names match Pulsar's {@code SubscriptionType} enum in spirit but stay decoupled so
 * this module doesn't pull in the Pulsar client jar.
 */
public enum SubscriptionType {
    FAILOVER,
    KEY_SHARED
}
