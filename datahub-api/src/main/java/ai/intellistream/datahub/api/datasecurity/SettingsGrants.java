// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.datasecurity;

import lombok.extern.slf4j.Slf4j;

import java.util.Collection;
import java.util.Locale;

/**
 * Who may read and change a tenant's own configuration, from the organization groups
 * {@code /settings/read} and {@code /settings/write}.
 *
 * <h3>Why not a dataset grant</h3>
 * The first version of the agent endpoints required an all-datasets write grant, on the reasoning
 * that an agent's tool list governs the whole tenant much as a dataset definition does. It worked,
 * but it said the wrong thing: it made "may configure the assistant" a consequence of "may write
 * every dataset", so the only way to let someone curate agents was to hand them every row of data
 * in the tenant. Configuration and data are different powers and now have different groups.
 *
 * <h3>The grammar</h3>
 * Two fixed paths, organization-scoped, with no wildcard and nothing to expand — settings are one
 * thing, not a hierarchy of things:
 *
 * <pre>
 * /settings/read
 * /settings/write
 * </pre>
 *
 * <p>Deliberately flat, unlike {@code /datasets/&lt;id&gt;/read}, and deliberately at the top of the
 * organization's tree rather than under {@code /datahub}: the grant parser refuses paths nested
 * deeper than its grammar, and there is no reason to spend that depth here.
 *
 * <p>Write does <strong>not</strong> imply read, matching how dataset grants treat the pair — an
 * automation that pushes configuration in need not be able to read it back. Grant both to a person.
 */
@Slf4j
public record SettingsGrants(boolean read, boolean write) {

    static final String READ_PATH = "/settings/read";
    static final String WRITE_PATH = "/settings/write";

    private static final SettingsGrants NONE = new SettingsGrants(false, false);
    private static final SettingsGrants ALL = new SettingsGrants(true, true);

    public static SettingsGrants none() {
        return NONE;
    }

    /** What {@code DATAHUB_ADMIN} gets: the cross-tenant operator escape hatch, as for datasets. */
    public static SettingsGrants all() {
        return ALL;
    }

    /**
     * Read the two flags out of a caller's organization group paths.
     *
     * <p>Anything else in the tree is ignored in silence, exactly as {@code DatasetGrants} ignores
     * everything outside {@code /datasets/}: an organization's group tree is theirs, and most of
     * what is in it has nothing to do with this platform.
     */
    public static SettingsGrants from(Collection<String> groupPaths) {
        if (groupPaths == null || groupPaths.isEmpty()) {
            return NONE;
        }
        boolean read = false;
        boolean write = false;
        for (String path : groupPaths) {
            if (path == null) {
                continue;
            }
            // Case-insensitive because a group tree is typed by hand, and matching exactly is a
            // rule nobody would guess had been applied when their grant silently did nothing.
            String normalized = path.strip().toLowerCase(Locale.ROOT);
            if (READ_PATH.equals(normalized)) {
                read = true;
            } else if (WRITE_PATH.equals(normalized)) {
                write = true;
            }
        }
        return read || write ? new SettingsGrants(read, write) : NONE;
    }
}
