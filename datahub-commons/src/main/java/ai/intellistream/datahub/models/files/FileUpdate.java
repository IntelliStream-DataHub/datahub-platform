// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.models.files;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.Map;
import java.util.Set;

/**
 * Partial update for an existing file or folder. The node is identified by {@code externalId} (or
 * {@code id}); every other field is optional and only applied when present (null means "leave
 * unchanged"). Supports rename ({@code name}), move ({@code path} = the new parent folder),
 * reassigning the dataset ({@code dataSetId}), and editing {@code description}, {@code source},
 * {@code metadata} and {@code relatedResources}.
 */
@Data
public class FileUpdate {

    @Schema(description = "Numeric id of the file or folder to update.", example = "5677892")
    private Long id;

    @Schema(description = "External id of the file or folder to update.", example = "file_sap_chemicals_csv")
    private String externalId;

    @Schema(description = "New name (rename). Null leaves the name unchanged.", example = "renamed.csv")
    private String name;

    @Schema(description = "New parent folder path (move). The folder is created if missing. "
            + "Null leaves the location unchanged.", example = "/reports/2026")
    private String path;

    @Schema(description = "Dataset id to assign. Cascades to descendants that have no dataset. "
            + "Null leaves the dataset unchanged.", example = "2323")
    private Long dataSetId;

    @Schema(description = "New description. Null leaves the description unchanged.",
            example = "Quarterly chemicals report.")
    private String description;

    @Schema(description = "New source. Null leaves the source unchanged.", example = "SAP")
    private String source;

    @Schema(description = "Replacement metadata map. Null leaves the metadata unchanged.")
    private Map<String, String> metadata;

    @Schema(description = "Replacement set of related resource ids. Null leaves them unchanged.")
    private Set<Long> relatedResources;
}
