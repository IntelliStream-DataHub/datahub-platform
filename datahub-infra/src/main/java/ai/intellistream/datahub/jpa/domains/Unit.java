// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.jpa.domains;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

import java.util.Set;
import java.util.TreeSet;

@Entity
@Table(name = "unit")
@Getter
@Setter
public class Unit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotNull
    @Size(min= 3, max = 256)
    private String externalId;

    @NotNull
    @Column(name = "external_id_hash")
    private Long externalIdHash;

    @NotNull
    @Size(min = 1, max = 64)
    private String name;

    @Size(min = 1, max = 256)
    private String longName;

    @Size(min = 1, max = 32)
    private String symbol;

    private String description;

    @ElementCollection
    @CollectionTable(name = "unit_alias_names", joinColumns = @JoinColumn(name = "unit_id"))
    @Column(name = "alias_names_text")
    private Set<String> aliasNames = new TreeSet<>();

    private String quantity;

    private Double conversionMultiplier;

    private Double conversionOffset;

    private String source;

    private String sourceReference;
}
