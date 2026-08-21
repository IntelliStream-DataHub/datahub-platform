// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.datasecurity;

import org.springframework.security.core.GrantedAuthority;

import java.util.Collection;
import java.util.Collections;
import java.util.Set;

/**
 * The per-request dataset access a caller has: two blanket flags plus the explicit dataset ids they
 * may read and write.
 *
 * <h2>Where these come from</h2>
 * <ul>
 *   <li>Everything is resolved by {@link DatasetPermissionsResolver} from the caller's Keycloak
 *       organization groups: group paths name datasets by {@code externalId}
 *       ({@link DatasetGrants}), each expanded through the {@code BELONGS_TO} hierarchy by
 *       {@link DatasetClosureService}, and the wildcard paths {@code /datasets/*&#47;read} and
 *       {@code /datasets/*&#47;write} set the <strong>blanket flags</strong> directly. Organization
 *       groups are scoped to one organization, so an all-datasets grant covers all datasets of that
 *       tenant and nothing else.</li>
 *   <li>The one remaining realm role is {@code DATAHUB_ADMIN}, the deliberately cross-tenant
 *       operator escape hatch: read <em>and</em> write everything, resolved from the token alone so
 *       operator access never depends on the UserInfo endpoint being reachable.</li>
 * </ul>
 * This class does no resolution of its own — it is the finished answer, so it stays cheap to pass
 * around and to hold for the lifetime of a WebSocket connection.
 *
 * <h2>Read and write are independent</h2>
 * A write grant grants <em>only</em> write access — it does not imply read, and the wildcard grants
 * follow the same rule. A caller needing both must hold both.
 */
public final class DatasetPermissions {

    /** Realm role {@code DATAHUB_ADMIN} → Spring authority. Grants read + write to every dataset. */
    static final String ADMIN = "ROLE_DATAHUB_ADMIN";

    private final boolean readAll;
    private final boolean writeAll;
    private final Set<Long> readableIds;
    private final Set<Long> writableIds;

    private DatasetPermissions(boolean readAll, boolean writeAll, Set<Long> readableIds, Set<Long> writableIds) {
        this.readAll = readAll;
        this.writeAll = writeAll;
        this.readableIds = readableIds;
        this.writableIds = writableIds;
    }

    /** A permission set granting nothing — used when there is no authenticated principal. */
    public static DatasetPermissions none() {
        return new DatasetPermissions(false, false, Collections.emptySet(), Collections.emptySet());
    }

    /** Read and write to every dataset — an admin, or a caller holding both wildcard groups. */
    public static DatasetPermissions allDatasets() {
        return new DatasetPermissions(true, true, Collections.emptySet(), Collections.emptySet());
    }

    /**
     * Build a permission set from the caller's blanket flags plus their already-expanded dataset
     * ids.
     *
     * <p>The id sets are defensively copied, so callers may pass mutable collections.
     */
    public static DatasetPermissions of(boolean readAll,
                                        boolean writeAll,
                                        Set<Long> readableIds,
                                        Set<Long> writableIds) {
        return new DatasetPermissions(
                readAll,
                writeAll,
                readableIds == null ? Collections.emptySet() : Set.copyOf(readableIds),
                writableIds == null ? Collections.emptySet() : Set.copyOf(writableIds));
    }

    /**
     * True if the authorities carry the {@code DATAHUB_ADMIN} realm role. Answered from the token's
     * authorities alone — deliberately, so an operator's access survives an identity-provider
     * outage that would fail everyone else closed.
     */
    public static boolean isAdmin(Collection<? extends GrantedAuthority> authorities) {
        if (authorities == null) {
            return false;
        }
        for (GrantedAuthority authority : authorities) {
            if (authority != null && ADMIN.equals(authority.getAuthority())) {
                return true;
            }
        }
        return false;
    }

    /** True if the caller may read every dataset. */
    public boolean canReadEverything() {
        return readAll;
    }

    /** True if the caller may write every dataset. */
    public boolean canWriteEverything() {
        return writeAll;
    }

    /** True if the caller may read the given dataset. */
    public boolean canRead(long datasetId) {
        return readAll || readableIds.contains(datasetId);
    }

    /**
     * Nullable-aware read check for a single dataset. A {@code null} dataset id represents an orphan
     * entity (no dataset), readable only by an all-datasets reader. Mirrors
     * {@link DataSecurity#canReadDataSet(Long)} but operates directly on an already-resolved
     * permission set — for callers off the request thread (e.g. WebSocket handlers).
     */
    public boolean canReadDataSet(Long datasetId) {
        return datasetId == null ? readAll : canRead(datasetId);
    }

    /** True if the caller may write the given dataset. */
    public boolean canWrite(long datasetId) {
        return writeAll || writableIds.contains(datasetId);
    }

    /**
     * The explicit set of dataset ids the caller may read. Empty when the caller has read-all
     * (callers should check {@link #canReadEverything()} first) or has no read access at all.
     */
    public Set<Long> readableIds() {
        return readableIds;
    }

    /**
     * The explicit set of dataset ids the caller may write. Empty when the caller has write-all
     * (callers should check {@link #canWriteEverything()} first) or has no write access at all.
     */
    public Set<Long> writableIds() {
        return writableIds;
    }
}
