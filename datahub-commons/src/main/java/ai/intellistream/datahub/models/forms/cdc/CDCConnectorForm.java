// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.models.forms.cdc;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.ZonedDateTime;

@Data
public class CDCConnectorForm {

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
    private String className;

    private ZonedDateTime dateCreated;

    private ZonedDateTime lastUpdated;
}
