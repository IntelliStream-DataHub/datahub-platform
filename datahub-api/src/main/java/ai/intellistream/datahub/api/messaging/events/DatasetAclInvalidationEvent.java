// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.messaging.events;

/**
 * Signals that something changed which alters what a dataset grant covers, so every cached ACL
 * closure for the tenant must be recomputed.
 *
 * <p>Fired by services inside their {@code @Transactional} methods and consumed
 * {@code AFTER_COMMIT}, for the same reason the Pulsar messages are: bumping the generation
 * <em>before</em> commit opens a race where another instance reads the new generation, recomputes
 * the closure from the not-yet-committed old state, and caches that as current. Over-invalidating
 * after a rollback is merely a cache miss; under-invalidating is a stale grant.
 *
 * @param tenantId the tenant whose closures to drop, carried explicitly because the listener may
 *                 run after the request's {@code TenantContext} has been cleared
 */
public record DatasetAclInvalidationEvent(String tenantId) {
}
