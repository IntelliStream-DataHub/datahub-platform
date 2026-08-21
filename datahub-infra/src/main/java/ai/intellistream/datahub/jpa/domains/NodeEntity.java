// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.jpa.domains;

import ai.intellistream.datahub.helpers.text.ExternalIds;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.ZonedDateTime;
import java.util.*;

@Entity
@Inheritance(strategy = InheritanceType.SINGLE_TABLE)
@DiscriminatorColumn(name = "node_type",discriminatorType = DiscriminatorType.INTEGER)
@Table(name = "node")
@Getter
@Setter
public class NodeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    protected Long id;

    @NotNull
    @Size(min= 3, max = 256)
    protected String externalId;

    @NotNull
    protected Long externalIdHash;

    @Setter
    @NotNull
    @Size(min= 3, max = 128)
    protected String name;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "node_type", insertable = false, updatable = false)
    protected NodeType nodeType;

    /**
     * The description of the asset.
     */
    protected String description;

    /**
     * The name of the data source containing the primary information about this node. Common to all
     * node types (stored in the shared {@code source} column of the single {@code node} table).
     */
    @Size(min = 0, max = 128)
    protected String source;

    /**
     * The id of the data set this asset belongs to.
     * <p>
     * No cascade: a {@link DatasetEntity} is shared by many nodes, so deleting a single node must
     * never cascade into deleting the dataset. Dataset lifecycle is managed independently.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "data_set_id",referencedColumnName = "id")
    protected DatasetEntity dataSet;

    @CreationTimestamp
    protected ZonedDateTime dateCreated;

    @UpdateTimestamp
    protected ZonedDateTime lastUpdated;

    /**
     * Optimistic-lock counter. Hibernate adds {@code AND version = ?} to every UPDATE and
     * increments the value; if two concurrent transactions both read the same version and
     * try to write, the second hits {@code 0 rows affected} and surfaces as
     * {@link org.springframework.orm.ObjectOptimisticLockingFailureException}. Also covers
     * the update-after-delete race — the row's gone, the WHERE matches nothing, caller gets
     * a 409 instead of silently losing the write and publishing a phantom CUD event.
     */
    @Version
    @Column(nullable = false)
    protected Long version;

    /**
     * If asset node is a root node
     */
    protected Boolean isRoot = false;

    /**
     * Switched off without being deleted. Only policies set this today: a policy is a node other
     * things point at and its findings outlive it, so it is deactivated rather than removed. Every
     * other node type defaults to active and ignores it.
     */
    @Column(name = "is_deactivated", nullable = false)
    protected boolean isDeactivated = false;

    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "node_metadata",
            joinColumns = @JoinColumn(name = "node_id"))
    @MapKeyColumn(name = "key")
    @Column(name = "value")
    @OnDelete(action = OnDeleteAction.CASCADE)
    protected Map<String,String> metadata = new HashMap<>();

    /**
     * A list of the labels associated with this asset.
     */
    @NotNull
    @NotEmpty
    protected String labels;

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
            name = "node_labels",
            joinColumns = @JoinColumn(name = "node_id"),
            inverseJoinColumns = @JoinColumn(name = "label_id")
    )
    protected Collection<Label> labelEntities;

    public void addToMetadata(String key,String Value ) {
        this.metadata.put(key,Value);
    }

    public void removeFromMetadata(String key) {
        this.metadata.remove(key);
    }

    /**
     * Stores the external id verbatim and derives {@code external_id_hash} from its lowercased form.
     *
     * <p>Those two halves are the whole design. Storage is byte-exact, so a plant tag
     * {@code COM-99-PT-1034} joins against the source system that issued it. The hash is
     * case-folded, so {@code com-99-pt-1034} collides with it on the existing unique index and is
     * rejected as the duplicate it is — and a lookup for either finds the one row.
     *
     * <p>Always derive the hash here, never from a raw string at the call site: setting one without
     * the other desynchronises the row from its own index.
     */
    public void setExternalId(String externalId){
        this.externalId = externalId;
        this.externalIdHash = externalId == null ? null : ExternalIds.hash(externalId);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof NodeEntity)) return false;
        return id != null && id.equals(((NodeEntity) o).getId());
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }

}
