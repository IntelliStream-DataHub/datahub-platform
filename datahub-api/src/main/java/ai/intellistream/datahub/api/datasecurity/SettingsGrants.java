// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.datasecurity;

import ai.intellistream.datahub.models.tenant.SettingsPermission;
import ai.intellistream.datahub.models.tenant.SettingsScopes;
import lombok.extern.slf4j.Slf4j;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

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
 * <p>Deliberately the same shape as {@link DatasetGrants}, so an administrator learns one convention
 * rather than two, and deliberately <em>not</em> the same code. Sharing a parser would mean a change
 * for settings could regress dataset access, which is the most sensitive authorisation in the
 * platform. The duplication is a short loop over strings with its own tests; unify it if a third
 * user of the grammar turns up.
 *
 * <p>Read and write are independent — a write grant does not imply read — and so is each scope:
 * whoever may change which model your assistant runs on, and what it costs you, is not
 * automatically whoever may change anything else that ends up under settings.
 *
 * <p>Paths are relative to the organization, which is what Keycloak emits in the
 * {@code organization.<alias>.groups} claim, so the tenant is already implicit — including for the
 * wildcard, which means every scope <em>of this organization</em>.
 *
 * <p>Anything that is not one of these paths is ignored. An organization's group tree is theirs and
 * may hold groups that have nothing to do with DataHub.
 *
 * @see SettingsScopes for the scopes that exist
 */
@Slf4j
public record SettingsGrants(boolean readAll, boolean writeAll,
                             Set<String> readScopes, Set<String> writeScopes) {

    private static final String PREFIX = "/settings/";
    private static final String ALL_SCOPES = "*";
    private static final String READ = "read";
    private static final String WRITE = "write";

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
        if (groupPaths == null || groupPaths.isEmpty()) {
            return NONE;
        }
        boolean readAll = false;
        boolean writeAll = false;
        // Case-insensitive, so a group written /Settings/LLM/read grants the same as /settings/llm/read
        // rather than silently granting nothing.
        Set<String> read = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        Set<String> write = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);

        for (String path : groupPaths) {
            if (path == null || !path.startsWith(PREFIX)) {
                continue;
            }
            String remainder = path.substring(PREFIX.length());
            int split = remainder.lastIndexOf('/');
            if (split <= 0 || split == remainder.length() - 1) {
                // "/settings/llm" (the container group, no permission) or a trailing slash.
                continue;
            }
            String scope = remainder.substring(0, split);
            String permission = remainder.substring(split + 1);
            if (scope.indexOf('/') >= 0) {
                // Deeper than the grammar allows. Refusing rather than guessing keeps an unintended
                // nesting from granting something nobody meant to grant.
                continue;
            }

            boolean everyScope = ALL_SCOPES.equals(scope);
            switch (permission.toLowerCase()) {
                case READ -> {
                    if (everyScope) {
                        readAll = true;
                    } else {
                        read.add(scope);
                    }
                }
                case WRITE -> {
                    if (everyScope) {
                        writeAll = true;
                    } else {
                        write.add(scope);
                    }
                }
                default -> log.debug("Ignoring organization group with unknown permission '{}': {}",
                        permission, path);
            }
        }

        if (!readAll && !writeAll && read.isEmpty() && write.isEmpty()) {
            return NONE;
        }
        return new SettingsGrants(readAll, writeAll,
                Collections.unmodifiableSet(read), Collections.unmodifiableSet(write));
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
