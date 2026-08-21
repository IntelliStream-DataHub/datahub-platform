// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.models;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

@Data
@Schema(name="FileUpload", description="File properties")
public class FileUpload {

    private String externalId;
    private String path;
    private String source;
    private String name;
    private String description;
    private String mimeType;

    @Schema(description = "The id of the data set for this file.", example = "2323")
    private Long dataSetId;

    private Set<Long> relatedResources = new HashSet<>();
    private Map<String, String> metadata = new HashMap<>();

}
