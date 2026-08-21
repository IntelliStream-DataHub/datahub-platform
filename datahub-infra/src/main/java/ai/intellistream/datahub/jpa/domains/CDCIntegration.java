// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.jpa.domains;

import ai.intellistream.datahub.helpers.text.TextValidator;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;
import net.openhft.hashing.LongHashFunction;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.ZonedDateTime;

@Entity
@Table(name = "cdc_integration")
@Getter
@Setter
public class CDCIntegration {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @NotNull
    @Size(min= 3, max = 256)
    private String externalId;

    @NotNull
    private Long hash;

    @NotNull
    @Size(min = 3, max = 512)
    private String name;

    private String description;

    @NotNull
    private Boolean deactivated = false;

    @CreationTimestamp
    private ZonedDateTime dateCreated;

    @UpdateTimestamp
    private ZonedDateTime lastUpdated;

    @NotNull
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "connector_id", referencedColumnName = "id")
    private CDCConnector connector;

    @NotNull
    private String dbHostname;

    private int dbport;

    @NotNull
    private String dbUser;

    @NotNull
    private String dbPassword;

    @NotNull
    private String dbName;

    @NotNull
    private String topicPrefix;

    private String dbPlugin;

    @NotNull
    private String tableIncludeList;

    @NotNull
    private String publicationName;

    @NotNull
    private String publicationAutocreateMode;

    @NotNull
    private String offsetStorage;

    @NotNull
    private String offsetStorageFilename;

    private int offsetFlushIntervalMs = 6000;

    @NotNull
    private String snapshotMode;

    public void setExternalId(String externalId){
        this.externalId = TextValidator.toSnakeLowerCasedAllowStartWithDigits(externalId);
        this.hash = LongHashFunction.xx3().hashChars(this.externalId);
    }

    public void setPublicationName(String publicationName){
        this.publicationName = TextValidator.toSnakeLowerCasedAllowStartWithDigits(publicationName);
    }

    public void setTopicPrefix(String topicPrefix){
        this.topicPrefix = TextValidator.toSnakeLowerCasedAllowStartWithDigits(topicPrefix);
    }
}
