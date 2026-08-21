// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.datasecurity;

import ai.intellistream.datahub.jpa.domains.NodeEntity;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Set;

/**
 * Authorises read and write access to datasets. This is the single entry point the service and
 * controller layers use; the resolution behind it lives in {@link DatasetPermissionsResolver}.
 *
 * <h2>Where permissions come from</h2>
 * <ul>
 *   <li><strong>All dataset grants</strong> are Keycloak <em>organization groups</em>. Per-dataset
 *       grants name the dataset by {@code externalId} ({@code /datasets/data_set_sap/read}) and are
 *       expanded through the {@code BELONGS_TO} hierarchy so a grant on a parent covers its
 *       descendants; the wildcard grants ({@code /datasets/*&#47;read|write}) cover every dataset in
 *       the organization. Organization groups are scoped to one organization, which is what makes
 *       both kinds safe in a multi-tenant realm — realm roles are not.</li>
 *   <li><strong>{@code DATAHUB_ADMIN}</strong> is the one realm role left: the deliberately
 *       cross-tenant operator escape hatch, read + write to everything, answered from the token
 *       alone.</li>
 * </ul>
 * Read and write are independent: a write grant does not confer read.
 *
 * <p>Keycloak stays the source of truth and nothing about access is administered in DataHub.
 * Permissions are resolved once per request and cached briefly, so a change lands within the cache
 * TTL rather than waiting for the caller's token to expire.
 *
 * <h2>Fail-closed semantics</h2>
 * <ul>
 *   <li>No authentication in context → deny.</li>
 *   <li>No matching role → deny.</li>
 *   <li>An entity with {@code dataSet == null} (orphan) is readable/writable only by a caller with
 *       the corresponding all-datasets grant.</li>
 * </ul>
 *
 * <h2>Keycloak setup</h2>
 * See {@code datahub-api/KEYCLOAK_ORG_GROUPS.md} for the organization, group and protocol-mapper
 * configuration, and {@code datahub-api/DATASET_ACL_SETUP.md} for what the grants gate.
 */
@Service
@Slf4j
public class DataSecurity {

    /** Spring authority for the {@code DATAHUB_ADMIN} realm role. Read + write to every dataset. */
    public static final String ADMIN_AUTHORITY = DatasetPermissions.ADMIN;

    // ---- Read ---------------------------------------------------------------------------------

    public boolean hasReadPermissionToDataSet(long dataSetId) {
        return permissions().canRead(dataSetId);
    }

    public boolean hasReadPermissionToDataSet(NodeEntity node) {
        if (node == null) return false;
        DatasetPermissions perms = permissions();
        // Orphan nodes (no dataset) can't be matched against dataset ACLs; only an all-datasets
        // reader may see them.
        if (node.getDataSet() == null) return perms.canReadEverything();
        return perms.canRead(node.getDataSet().getId());
    }

    /**
     * Nullable-aware read check. A {@code null} dataset id represents an entity not attached to any
     * dataset (orphan), which only an all-datasets reader may access.
     */
    public boolean canReadDataSet(Long dataSetId) {
        DatasetPermissions perms = permissions();
        return dataSetId == null ? perms.canReadEverything() : perms.canRead(dataSetId);
    }

    /** True when the caller may read every dataset (admin or a read-all role). */
    public boolean hasReadAccessToEverything() {
        return permissions().canReadEverything();
    }

    /**
     * The explicit set of dataset ids the caller may read. Empty when the caller can read
     * everything (check {@link #hasReadAccessToEverything()} first) or has no read access — callers
     * narrowing a list query must not treat an empty set as "read all".
     */
    public Set<Long> readableDataSetIds() {
        return permissions().readableIds();
    }

    /** Throws {@link DatasetAccessDeniedException} if the caller may not read the given dataset id. */
    public void assertCanReadDataSet(Long dataSetId) {
        if (!canReadDataSet(dataSetId)) {
            throw new DatasetAccessDeniedException("read", dataSetId);
        }
    }

    /** Throws {@link DatasetAccessDeniedException} if the caller may not read the given node's dataset. */
    public void assertCanRead(NodeEntity node) {
        if (!hasReadPermissionToDataSet(node)) {
            Long id = (node != null && node.getDataSet() != null) ? node.getDataSet().getId() : null;
            throw new DatasetAccessDeniedException("read", id);
        }
    }

    // ---- Write --------------------------------------------------------------------------------

    public boolean hasWritePermissionToDataSet(long dataSetId) {
        return permissions().canWrite(dataSetId);
    }

    public boolean hasWritePermissionToDataSet(NodeEntity node) {
        if (node == null) return false;
        DatasetPermissions perms = permissions();
        if (node.getDataSet() == null) return perms.canWriteEverything();
        return perms.canWrite(node.getDataSet().getId());
    }

    /**
     * Nullable-aware write check. A {@code null} dataset id represents writing an entity not
     * attached to any dataset (orphan), which only an all-datasets writer may do.
     */
    public boolean canWriteToDataSet(Long dataSetId) {
        DatasetPermissions perms = permissions();
        return dataSetId == null ? perms.canWriteEverything() : perms.canWrite(dataSetId);
    }

    /** True when the caller may write every dataset (admin or the write-all role). */
    public boolean hasWriteAccessToEverything() {
        return permissions().canWriteEverything();
    }

    /**
     * The explicit set of dataset ids the caller may write. Empty when the caller can write
     * everything (check {@link #hasWriteAccessToEverything()} first) or has no write access.
     */
    public Set<Long> writableDataSetIds() {
        return permissions().writableIds();
    }

    /** Throws {@link DatasetAccessDeniedException} if the caller may not write the given dataset id. */
    public void assertCanWriteDataSet(Long dataSetId) {
        if (!canWriteToDataSet(dataSetId)) {
            throw new DatasetAccessDeniedException("write", dataSetId);
        }
    }

    // ---- Managing the datasets themselves -----------------------------------------------------

    /**
     * True if the caller may create, update or delete <strong>datasets</strong>, as opposed to the
     * data inside them. Requires an all-datasets write grant: the {@code /datasets/*&#47;write}
     * organization group, or {@code DATAHUB_ADMIN}.
     *
     * <p>Deliberate, and stricter than it needs to be. A dataset is the unit access is granted on,
     * so creating one, renaming it or moving it in the hierarchy changes what existing grants
     * cover. Per-dataset grants therefore never confer it; the wildcard write group keeps it
     * delegable to a tenant's own data steward without touching other tenants.
     *
     * <p>This is the same answer {@link #canWriteToDataSet(Long)} gives for a {@code null} dataset,
     * because a dataset node has no {@code data_set_id} of its own and so reads as an orphan. It is
     * stated separately anyway: relying on that coincidence left the rule undocumented, produced a
     * confusing "no write permission for data set: null" denial, and would quietly disappear if
     * anyone revisited the orphan handling.
     */
    public boolean canManageDataSets() {
        return permissions().canWriteEverything();
    }

    /**
     * Throws {@link DatasetAccessDeniedException} unless the caller may create, update or delete
     * datasets. See {@link #canManageDataSets()}.
     */
    public void assertCanManageDataSets() {
        if (!canManageDataSets()) {
            throw DatasetAccessDeniedException.datasetManagement();
        }
    }

    /** Throws {@link DatasetAccessDeniedException} if the caller may not write the given node's dataset. */
    public void assertCanWrite(NodeEntity node) {
        if (!hasWritePermissionToDataSet(node)) {
            Long id = (node != null && node.getDataSet() != null) ? node.getDataSet().getId() : null;
            throw new DatasetAccessDeniedException("write", id);
        }
    }

    // ---- Internals ----------------------------------------------------------------------------

    private final DatasetPermissionsResolver resolver;

    public DataSecurity(DatasetPermissionsResolver resolver) {
        this.resolver = resolver;
    }

    private DatasetPermissions permissions() {
        return resolver.forCurrentRequest();
    }
}
