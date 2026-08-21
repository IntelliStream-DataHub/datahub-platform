// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.tenant;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Per-tenant file storage configuration, loaded from the tenant's Vault entry under the
 * {@code file-storage} key:
 *
 * <pre>
 * "file-storage": {
 *   "host": "localhost",
 *   "root-path": "/opt/files-root/root/&lt;tenant&gt;",
 *   "trash-path": "/opt/files-root/trash/&lt;tenant&gt;"
 * }
 * </pre>
 *
 * {@code rootPath}/{@code trashPath} are the absolute, tenant-specific paths used directly by
 * {@code FilesConfig} for file operations (they already include the tenant segment, so no
 * organization-name suffix is appended). {@code host} is currently informational — file
 * operations are local-filesystem.
 */
@Data
@NoArgsConstructor
public class FileStorage {

    private String host;

    @JsonProperty("root-path")
    private String rootPath;

    @JsonProperty("trash-path")
    private String trashPath;
}
