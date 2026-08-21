// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.jpa.domains;

import ai.intellistream.datahub.helpers.text.TextValidator;
import jakarta.persistence.*;
import lombok.*;
import net.openhft.hashing.LongHashFunction;
import org.hibernate.annotations.CreationTimestamp;
import org.springframework.data.annotation.LastModifiedDate;

import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Entity
@Table(name = "governance_template")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@ToString(onlyExplicitlyIncluded = true)
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class GovernanceTemplate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @EqualsAndHashCode.Include
    @ToString.Include
    private Long id;

    @ToString.Include
    private String name;

    private String description;

    @Column(name = "external_id")
    private String externalId;

    @Column(name = "external_id_hash")
    private Long externalIdHash;

    @ElementCollection
    @CollectionTable(name = "governance_template_metadata",
            joinColumns = @JoinColumn(name = "node_id"))
    @MapKeyColumn(name = "key")
    @Column(name = "value")
    protected Map<String,String> metadata = new HashMap<>();

    @CreationTimestamp
    private ZonedDateTime createdAt;
    @LastModifiedDate
    private ZonedDateTime updatedAt;

    public void setExternalId(String externalId) {
        this.externalId = TextValidator.toSnakeLowerCasedAllowStartWithDigits(externalId);
        this.externalIdHash = LongHashFunction.xx3().hashChars(this.externalId);
    }
}



