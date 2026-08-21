// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.tenant;

/**
 * Published by {@link TenantConfigService} when a tenant disappears from a Vault refresh. Listeners
 * release per-tenant resources (e.g. the cached ClickHouse client). Carries only the id because the
 * tenant's config is, by definition, no longer available.
 */
public record TenantRemovedEvent(String tenantId) {
}
