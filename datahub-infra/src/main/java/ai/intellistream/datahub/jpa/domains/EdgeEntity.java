// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.jpa.domains;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

@Entity
@Table(name = "edge")
@Getter @Setter
public class EdgeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Many edges share one relationship type, so this is @ManyToOne. It was @OneToOne, which made
     * Hibernate generate a UNIQUE constraint on relationship_type_id — "at most one edge per
     * relationship type". Invisible in production, where ddl-auto is none and the Flyway schema
     * (V1__create_tables.sql) has only the composite UNIQUE (rel_start, rel_end,
     * relationship_type_id), but it broke any schema generated from the entities.
     */
    @NotNull
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "relationship_type_id", referencedColumnName = "id")
    private RelationshipType relationshipType;

    @NotNull
    @Column(name = "rel_start")
    private Long start;

    @NotNull
    @Column(name = "rel_end")
    private Long end;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "edge_metadata",
            joinColumns = @JoinColumn(name = "edge_id"))
    @MapKeyColumn(name = "key")
    @Column(name = "value")
    @OnDelete(action = OnDeleteAction.CASCADE)
    protected Map<String,String> metadata = new HashMap<>();

    @OneToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "data_set_id", referencedColumnName = "id")
    private NodeEntity dataSet;

    private String description;

    @CreationTimestamp
    private ZonedDateTime dateCreated;

    @UpdateTimestamp
    private ZonedDateTime lastUpdated;

    /**
     * Optimistic-lock counter. See {@link NodeEntity#version} for the rationale — the same
     * update-after-delete race exists for edges (e.g. reparent an edge while another request
     * removes it) and the same 409-via-ConcurrencyExceptionHandler flow catches it here.
     */
    @Version
    @Column(nullable = false)
    private Long version;
}
