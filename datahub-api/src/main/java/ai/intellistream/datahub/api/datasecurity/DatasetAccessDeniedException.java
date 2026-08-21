// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.datasecurity;

import org.springframework.security.access.AccessDeniedException;

/**
 * Thrown when a caller is denied read or write access to a specific dataset. Extends
 * {@link AccessDeniedException} so it still maps to HTTP 403, but carries the offending
 * {@code dataSetId} and the attempted {@code permission} ({@code "read"} / {@code "write"}) so the
 * error handler can render a machine-readable {@code application/problem+json} body.
 */
public class DatasetAccessDeniedException extends AccessDeniedException {

    private final Long dataSetId;
    private final String permission;

    public DatasetAccessDeniedException(String permission, Long dataSetId) {
        this("No " + permission + " permission for data set: " + dataSetId, permission, dataSetId);
    }

    private DatasetAccessDeniedException(String message, String permission, Long dataSetId) {
        super(message);
        this.permission = permission;
        this.dataSetId = dataSetId;
    }

    /**
     * Denial of dataset <em>management</em> (create / update / delete of a dataset itself), which
     * needs an all-datasets write grant. Named separately because the generic message would say
     * "for data set: null" and send the reader looking for a dataset that was never the point.
     */
    public static DatasetAccessDeniedException datasetManagement() {
        return new DatasetAccessDeniedException(
                "Creating, updating or deleting a data set requires an all-datasets write grant "
                        + "(the /datasets/*/write organization group, or DATAHUB_ADMIN). "
                        + "A grant on individual data sets is not enough.",
                "manage", null);
    }

    /** The dataset the caller was denied, or {@code null} for an orphan entity (no dataset). */
    public Long getDataSetId() {
        return dataSetId;
    }

    /** The attempted access: {@code "read"} or {@code "write"}. */
    public String getPermission() {
        return permission;
    }
}
