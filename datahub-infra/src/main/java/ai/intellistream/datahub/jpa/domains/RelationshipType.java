// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.jpa.domains;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import net.openhft.hashing.LongHashFunction;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.ZonedDateTime;

@Entity
@Table(name = "relationship_type")
@Getter
@Setter
@JsonIgnoreProperties(value = { "dateCreated", "lastUpdated", "hash" })
public class RelationshipType {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotNull
    private Long hash;

    @NotNull
    private String name;

    private String description;

    private String i18nCode;

    @CreationTimestamp
    private ZonedDateTime dateCreated;

    @UpdateTimestamp
    private ZonedDateTime lastUpdated;

    public void setName(String name){
        // Last line of defence for every creation path (REST, MCP tool, find-or-create,
        // function bindings). A blank or symbol-only name would otherwise persist an empty /
        // meaningless type and, worse, collide on relationship_hash_key (every blank hashes to
        // the same value). Require at least one letter or digit.
        if (name == null || name.chars().noneMatch(Character::isLetterOrDigit)) {
            throw new IllegalArgumentException("Relationship type name must not be blank");
        }
        this.name = name.toUpperCase();
        this.hash = LongHashFunction.xx3().hashChars(this.name);
    }

    public void setI18nCode(String i18nCode){
        if(i18nCode != null){
            this.i18nCode = i18nCode.toLowerCase();
        }
    }
}
