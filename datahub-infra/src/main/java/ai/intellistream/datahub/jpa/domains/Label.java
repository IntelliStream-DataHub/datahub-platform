// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.jpa.domains;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;
import ai.intellistream.datahub.helpers.text.Labels;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.ZonedDateTime;
import java.util.HashSet;
import java.util.Set;

@Entity
@Table(name = "label")
@Getter
@Setter
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Label {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @JsonProperty
    private Long id;

    @NotNull
    @JsonIgnore
    private Long hash;

    @NotNull
    @Size(min = 2, max = 512)
    @JsonProperty
    private String name;

    @JsonProperty
    private String description;

    @JsonProperty
    private String i18nCode;

    @NotNull
    @Size(max = 7)
    @JsonProperty
    private String color;

    @ManyToMany(mappedBy = "labelEntities")
    @JsonIgnore
    private Set<NodeEntity> nodes = new HashSet<>();

    @CreationTimestamp
    @JsonIgnore
    private ZonedDateTime dateCreated;

    @UpdateTimestamp
    @JsonIgnore
    private ZonedDateTime lastUpdated;

    public void setName(String name){
        // Single canonicaliser for label names, matching LabelForm.setName: toSnakeUpperCased
        // (strip leading digits, upper-case, snake special chars). Every persistence path funnels
        // through here, so the stored name — and the hash derived from it — are consistent no matter
        // how the label was created (label API vs auto-created from a resource reference).
        //
        // Both steps live in Labels now, because querying by label has to reproduce them exactly:
        // NodeFilter.getLabelHashes() matches on this hash, and a second implementation that
        // canonicalised even slightly differently would return an empty result rather than an error.
        this.name = Labels.canonical(name);
        this.hash = Labels.hash(name);
    }

    public void setI18nCode(String i18nCode){
        if(i18nCode != null){
            this.i18nCode = i18nCode.toLowerCase();
        }
    }
}
