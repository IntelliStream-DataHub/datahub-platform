// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.models.files;
import tools.jackson.databind.annotation.JsonSerialize;
import ai.intellistream.datahub.json.ToStringSerializer;

import ai.intellistream.datahub.helpers.text.HumanReadableHelper;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.time.ZonedDateTime;
import java.util.*;

@Schema(name="IndexNode", description="Index node is either a file or folder.")
@JsonInclude(JsonInclude.Include.NON_NULL)
@NoArgsConstructor
@EqualsAndHashCode(of = {"id", "externalId"}, callSuper = false)
@Data
@JsonPropertyOrder({"id", "externalId", "type", "name", "path", "*"})
public class IndexNode implements Comparable<IndexNode> {

    @Schema(description = "The id of the index node.", example = "5677892")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    @Schema(description = "File or folder name", example = "sap_chemicals.csv")
    private String name;

    @Schema(description = "Index node description", example = "Data pulled daily from Kyoto Systems.")
    private String description;

    @Schema(description = "The external id of the index node.", example = "file_sap_chemicals_csv")
    private String externalId;

    @Schema(description = "The path of the node, unix style.", example = "/path/to/foo/bar")
    private String path;

    @Schema(description = "File size in bytes.", example = "1024")
    private Long size;

    @Schema(description = "Hexadecimal representation of the file checksum. The algorithm is SHA-256.",
            example = "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08")
    private String checksum;

    @Schema(description = "Define what is the source for the file.", example = "Kyoto Systems")
    private String source;

    @Schema(description = "Index node type, file or folder.", example = "MyDocuments")
    private String type;

    @Schema(description = "File mime type", example = "pdf")
    private String mimeType;

    @Schema(description = "When file was created.", example = "2000-01-01 12:00")
    private ZonedDateTime sourceDateCreated;

    @Schema(description = "When file was last updated.", example = "2000-01-01 18:00")
    private ZonedDateTime sourceLastUpdated;

    @Schema(description = "When file was uploaded to IntelliStream DataHub.", example = "2024-01-01 12:00")
    private ZonedDateTime dateCreated;

    @Schema(description = "When file was last updated in IntelliStream DataHub.", example = "2024-01-01 18:00")
    private ZonedDateTime lastUpdated;

    /**
     * When the file was moved to the trash, or absent while it is live.
     *
     * <p>Only {@code GET /files/trash} returns nodes that have it. Absent rather than null on a live
     * node, so nothing changes for a client reading a normal listing — the same way {@code warnings}
     * and {@code nextCursor} were added to the response envelope.
     *
     * <p>Before this field the deletion time was only available by parsing it out of the
     * {@code externalId}, which delete rewrote to {@code DELETED_..._<epochMillis>}. The trash
     * listing now returns the file's real external id and the time as a proper timestamp.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Schema(description = "When the file was moved to the trash. Absent unless the file is in the trash.",
            example = "2026-01-01 18:00")
    private ZonedDateTime deletedAt;

    @Schema(description = "Parent Index Node id, always a folder.", example = "MyDocuments")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long parentId;

    @Schema(description = "Parent Index Node external id, always a folder.", example = "MyDocuments")
    private String parentExternalId;

    @Schema(description = "The id of the data set for this index node.", example = "2323")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long dataSetId;

    @Schema(description = "File or folder metadata, additional fields you can bind information with.", example = "{\"definition\": \"SAP ORDER PLACED\"}")
    private Map<String, String> metadata = new HashMap<>();

    @Schema(description = "Collection of id for resources that this file or folder has a relation to.", example = "[2323, 34, 166]")
    private Set<Long> relatedResources = new TreeSet<>();

    @Schema(description = "A collection of security categories.", example = "[33,5,128]")
    private Set<Integer> securityCategories = new TreeSet<>();

    public boolean isFolder() {
        return this.type.equals("FOLDER");
    }

    public boolean isFile() {
        return this.type.equals("FILE");
    }

    public boolean isRoot() {
        return this.parentId == null;
    }

    public String getHumanReadableSize() {
        return HumanReadableHelper.getSize(this.size);
    }

    @Override
    public int compareTo(@NotNull IndexNode other) {
        int typeComparison;
        if (this.type != null && other.type != null) {
            if (this.type.equals("FOLDER") && other.type.equals("FILE")) {
                typeComparison = -1; // FOLDER comes before other FILE
            } else if (this.type.equals("FILE") && other.type.equals("FOLDER")) {
                typeComparison = 1;  // FILE comes after other FOLDER
            } else {
                // Both are FOLDER, or both are FILE, or other types,
                // compare them alphabetically as a fallback for consistency
                typeComparison = this.type.compareTo(other.type);
            }
        } else if (this.type != null) {
            typeComparison = -1; // this.type is not null, other.type is null, so this comes first
        } else if (other.type != null) {
            typeComparison = 1; // this.type is null, other.type is not null, so other comes first
        } else {
            typeComparison = 0; // Both types are null
        }

        if (typeComparison != 0) {
            return typeComparison;
        }

        // If types are the same, compare by name
        if (this.name != null && other.name != null) {
            return this.name.compareTo(other.name);
        } else if (this.name != null) {
            return -1; // this.name is not null, other.name is null
        } else if (other.name != null) {
            return 1; // this.name is null, other.name is not null
        }

        return 0; // Both type and name are null or equal
    }

    public static List<IndexNode> sort(List<IndexNode> nodes) {
        if (nodes == null || nodes.isEmpty()) {
            return nodes;
        }

        TreeSet<IndexNode> sortedSet = new TreeSet<>(nodes);
        return sortedSet.stream().toList();
    }

}
