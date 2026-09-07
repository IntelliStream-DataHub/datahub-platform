// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.datasecurity;

import ai.intellistream.datahub.models.tenant.SettingsPermission;
import ai.intellistream.datahub.models.tenant.SettingsScopes;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Who may see and change each of a tenant's own settings, parsed out of their Keycloak organization
 * group paths.
 *
 * <h2>Grammar</h2>
 * <pre>
 *   /settings/&lt;scope&gt;/read
 *   /settings/&lt;scope&gt;/write
 *   /settings/*&#47;read           (every scope, including ones added later)
 *   /settings/*&#47;write
 * </pre>
 *
 * <p>One instance of the shared {@link GrantGrammar} — the same convention as
 * {@link DatasetGrants}, so an administrator learns one grammar rather than two. The dataset
 * grants are the most sensitive authorisation in the platform, so the shared parser's behaviour
 * is pinned by the dataset test suite: a parsing change made for settings that would alter what a
 * dataset group grants fails those tests before it ships.
 *
 * <p>Read and write are independent — a write grant does not imply read — and so is each scope:
 * whoever may change which model your assistant runs on, and what it costs you, is not
 * automatically whoever may change anything else that ends up under settings.
 *
 * <p>Paths are relative to the organization, which is what Keycloak emits in the
 * {@code organization.<alias>.groups} claim, so the tenant is already implicit — including for the
 * wildcard, which means every scope <em>of this organization</em>.
 *
 * @see SettingsScopes for the scopes that exist
 */
public record SettingsGrants(boolean readAll, boolean writeAll,
                             Set<String> readScopes, Set<String> writeScopes) {

    private static final String READ = "read";
    private static final String WRITE = "write";
    /** A new verb on settings starts here, and gets surfaced alongside canRead/canWrite. */
    private static final GrantGrammar GRAMMAR = GrantGrammar.of("settings", READ, WRITE);

    private static final SettingsGrants NONE =
            new SettingsGrants(false, false, Collections.emptySet(), Collections.emptySet());

    public static SettingsGrants none() {
        return NONE;
    }

    /** Every scope, for {@code DATAHUB_ADMIN} — the cross-tenant operator escape hatch. */
    public static SettingsGrants all() {
        return new SettingsGrants(true, true, Collections.emptySet(), Collections.emptySet());
    }

    public boolean isEmpty() {
        return !readAll && !writeAll && readScopes.isEmpty() && writeScopes.isEmpty();
    }

    public boolean canRead(String scope) {
        return readAll || readScopes.contains(scope);
    }

    public boolean canWrite(String scope) {
        return writeAll || writeScopes.contains(scope);
    }

    public static SettingsGrants from(Collection<String> groupPaths) {
        GrantGrammar.Grants grants = GRAMMAR.parse(groupPaths);
        if (grants.isEmpty()) {
            return NONE;
        }
        return new SettingsGrants(grants.allowsAll(READ), grants.allowsAll(WRITE),
                grants.objects(READ), grants.objects(WRITE));
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
