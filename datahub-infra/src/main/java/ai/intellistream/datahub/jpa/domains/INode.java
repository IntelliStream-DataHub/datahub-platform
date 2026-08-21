// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.jpa.domains;

import ai.intellistream.datahub.helpers.text.TextValidator;
import ai.intellistream.datahub.helpers.utils.IdGenerator;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import net.openhft.hashing.LongHashFunction;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.ZonedDateTime;
import java.util.*;

@Entity
@Table(name = "inodes") // New table for INode, or we can reuse 'file' table and add new columns
@Inheritance(strategy = InheritanceType.SINGLE_TABLE) // Example strategy, can be changed
@Getter
@Setter
@Slf4j
public class INode {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotNull
    @Size(min= 3, max = 256)
    @Column(name = "external_id")
        private String externalId;

    @NotNull
    @Column(name = "external_id_hash")
    private Long externalIdHash;

    @Size(min = 1, max = 256)
    @Pattern(regexp = "^[a-zA-Z0-9_.-]+$", message = "Field must contain only letters, digits, and the special characters _, -, .")
    private String name;
    private String description;

    private Long size;

    @Column(name = "checksum")
    private byte[] checksum;

    @Enumerated(EnumType.ORDINAL)
    @Column(name = "node_type")
    private INodeType nodeType;

    @Column(name = "mime_type")
    private String mimeType;

    private String source;

    private String labels;

    @Column(name = "source_date_created")
    private ZonedDateTime sourceDateCreated;

    @Column(name = "source_last_updated")
    private ZonedDateTime sourceLastUpdated;

    @CreationTimestamp
    @Column(name = "date_created")
    private ZonedDateTime dateCreated;

    @UpdateTimestamp
    @Column(name = "last_updated")
    private ZonedDateTime lastUpdated;

    @Column(name = "is_deleted")
    private boolean isDeleted = false;

    @OneToOne(fetch = FetchType.EAGER, cascade = CascadeType.ALL)
    @JoinColumn(name = "data_set_id")
    private NodeEntity dataSet;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "inode_metadata",
            joinColumns = @JoinColumn(name = "inode_id"))
    @MapKeyColumn(name = "key")
    @Column(name = "value")
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Map<String, String> metadata = new HashMap<>();

    @ManyToMany(fetch = FetchType.EAGER)
    @JoinTable(
            name = "inode_labels",
            joinColumns = @JoinColumn(name = "inode_id"),
            inverseJoinColumns = @JoinColumn(name = "label_id")
    )
    private Set<Label> labelEntities;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_id")
    private INode parent;

    @NotNull
    @Column(name = "path")
    private String path;

    @NotNull
    @Column(name = "path_hash")
    private Long pathHash;

    // Symlink specific (if needed)
    @Column(name = "target_path")
    private String targetPath;

    @Column(name = "target_path_hash")
    private Long targetPathHash;

    // Lazy like parent: fetched on demand via @EntityGraph on the directory-listing queries (which
    // transform nodes to DTOs outside any session, open-in-view is off), so those queries must list
    // "relatedResources" in their graph or FileTransformer.transform throws LazyInitializationException.
    @ElementCollection
    @CollectionTable(name = "inode_related_resources", joinColumns = @JoinColumn(name = "inode_id"))
    @Column(name = "resource_id")
    private Set<Long> relatedResources = new HashSet<>();

    @ElementCollection
    @CollectionTable(name = "inodes_security_categories", joinColumns = @JoinColumn(name = "inode_id"))
    @Column(name = "security_category")
    private Set<Integer> securityCategories = new TreeSet<>();

    public void setExternalId(String externalId){
        this.externalId = TextValidator.toSnakeLowerCasedAllowStartWithDigits(externalId);
        this.externalIdHash = LongHashFunction.xx3().hashChars(this.externalId);
    }

    public void setPath(String path){
        // Validate then hash the NORMALIZED value, so a rejected input doesn't leave the entity
        // with a null path but a hash of the original (potentially traversal-laden) input.
        String normalized;
        try {
            normalized = TextValidator.pathName(path);
        } catch (RuntimeException e) {
            log.error("Rejected invalid path: {}", e.getMessage());
            this.path = null;
            this.pathHash = 0L;
            return;
        }
        this.path = normalized;
        this.pathHash = IdGenerator.xxHash(normalized);
    }

    public void addToMetadata(String key, String value) {
        this.metadata.put(key, value);
    }

    public void setDefaultMimeType(String mimeType){
        if(mimeType == null || mimeType.isBlank() || mimeType.equals("null")) {
            this.mimeType = "auto-detect";
        }
        else this.mimeType = mimeType.toLowerCase();
    }

    public void removeFromMetadata(String key) {
        this.metadata.remove(key);
    }

    public enum INodeType {
        FILE,
        FOLDER,
        SYMLINK
    }

    @Override
    public String toString() {
        return new StringJoiner(", ", INode.class.getSimpleName() + "[", "]")
                .add("id=" + id)
                .add("externalId='" + externalId + "'")
                .add("externalIdHash=" + externalIdHash)
                .add("name='" + name + "'")
                .add("description='" + description + "'")
                .add("size=" + size)
                .add("checksum=" + Arrays.toString(checksum))
                .add("nodeType=" + nodeType)
                .add("mimeType='" + mimeType + "'")
                .add("source='" + source + "'")
                .add("labels='" + labels + "'")
                .add("sourceDateCreated=" + sourceDateCreated)
                .add("sourceLastUpdated=" + sourceLastUpdated)
                .add("dateCreated=" + dateCreated)
                .add("lastUpdated=" + lastUpdated)
                .add("isDeleted=" + isDeleted)
                .add("dataSet=" + dataSet)
                .add("metadata=" + metadata)
                .add("labelEntities=" + labelEntities)
                .add("parent=" + parent)
                .add("path='" + path + "'")
                .add("pathHash=" + pathHash)
                .add("targetPath='" + targetPath + "'")
                .add("targetPathHash=" + targetPathHash)
                .add("relatedResources=" + relatedResources)
                .add("securityCategories=" + securityCategories)
                .toString();
    }
}
