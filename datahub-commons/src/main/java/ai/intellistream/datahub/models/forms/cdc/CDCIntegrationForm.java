// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.models.forms.cdc;

import ai.intellistream.datahub.helpers.text.TextValidator;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;
import net.openhft.hashing.LongHashFunction;

import java.time.ZonedDateTime;

@Data
public class CDCIntegrationForm {

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

    private ZonedDateTime dateCreated;

    private ZonedDateTime lastUpdated;

    @NotNull
    private CDCConnectorForm connector;

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

    private int offsetFlushIntervalMs;

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
