// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.datasecurity;

import ai.intellistream.datahub.models.tenant.SettingsPermission;
import ai.intellistream.datahub.models.tenant.SettingsScopes;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Who may see and change each of a tenant's own settings.
 *
 * <pre>
 *   /settings/llm/read      /settings/llm/write
 *   /settings/*&#47;read       /settings/*&#47;write      (every scope, present and future)
 * </pre>
 *
 * <p>The same grammar as dataset grants, and the same parser — {@link ScopedGrants}. Settings were
 * briefly a flat {@code /settings/read|write} pair, which does not survive a second scope: whoever
 * may change which model your assistant runs on, and what it costs you, is not automatically
 * whoever may change anything else that ends up under settings.
 *
 * @see SettingsScopes for the scopes that exist
 */
public record SettingsGrants(ScopedGrants scoped) {

    private static final String PREFIX = "settings";

    public static SettingsGrants none() {
        return new SettingsGrants(ScopedGrants.none());
    }

    /** Every scope, for {@code DATAHUB_ADMIN} — the cross-tenant operator escape hatch. */
    public static SettingsGrants all() {
        return new SettingsGrants(new ScopedGrants(true, true, java.util.Set.of(), java.util.Set.of()));
    }

    public static SettingsGrants from(Collection<String> groupPaths) {
        return new SettingsGrants(ScopedGrants.from(groupPaths, PREFIX));
    }

    public boolean canRead(String scope) {
        return scoped.canRead(scope);
    }

    public boolean canWrite(String scope) {
        return scoped.canWrite(scope);
    }

    /**
     * These grants over every scope the platform knows, which is the shape a client needs: the
     * wildcard is already resolved, so a caller holding {@code /settings/*&#47;read} sees read on
     * each scope by name rather than a wildcard it would have to expand itself.
     */
    public Map<String, SettingsPermission> byScope() {
        Map<String, SettingsPermission> permissions = new LinkedHashMap<>();
        for (String scope : SettingsScopes.ALL) {
            permissions.put(scope, new SettingsPermission(canRead(scope), canWrite(scope)));
        }
        return permissions;
    }
}
