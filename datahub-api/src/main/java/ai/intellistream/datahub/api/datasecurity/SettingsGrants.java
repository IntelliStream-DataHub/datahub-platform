// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.datasecurity;

import java.util.Collection;

/**
 * Who may see and change a tenant's own settings, from that tenant's Keycloak organization groups.
 *
 * <p>Two paths, no hierarchy and no wildcards: {@code /settings/read} and {@code /settings/write}.
 * Dataset grants need a tree because datasets form one; settings are a single object per tenant, so
 * a grant is simply held or not.
 *
 * <p>Write does <strong>not</strong> imply read. They are separate group memberships and a
 * deployment that grants only one gets only one — {@link #canWrite()} is asked before a save and
 * {@link #canRead()} before a load, and nothing infers either from the other. Granting both is the
 * ordinary case and is what the bootstrap script sets up.
 *
 * @see DatasetGrants for the same idea over datasets, where the hierarchy does matter
 */
public record SettingsGrants(boolean canRead, boolean canWrite) {

    private static final String READ = "/settings/read";
    private static final String WRITE = "/settings/write";

    public static SettingsGrants none() {
        return new SettingsGrants(false, false);
    }

    /** Both, for {@code DATAHUB_ADMIN} — the cross-tenant operator escape hatch. */
    public static SettingsGrants all() {
        return new SettingsGrants(true, true);
    }

    /**
     * Reads the two paths out of a caller's group list. Paths are matched exactly and
     * case-sensitively, as Keycloak stores them; anything else in the list belongs to another
     * grant and is ignored.
     */
    public static SettingsGrants from(Collection<String> groupPaths) {
        if (groupPaths == null || groupPaths.isEmpty()) {
            return none();
        }
        boolean read = false;
        boolean write = false;
        for (String path : groupPaths) {
            if (READ.equals(path)) {
                read = true;
            } else if (WRITE.equals(path)) {
                write = true;
            }
        }
        return new SettingsGrants(read, write);
    }
}
