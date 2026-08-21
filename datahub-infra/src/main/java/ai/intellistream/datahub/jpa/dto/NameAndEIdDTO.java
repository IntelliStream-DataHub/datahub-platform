// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.jpa.dto;


import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

/**
 * DTO for CriteriaBuilders, don't use with repositories
 */
@Getter
@Setter
@AllArgsConstructor
public class NameAndEIdDTO {

    private Long id;
    private String name;
    private String externalId;

    public NameAndEIdDTO(Integer id, String name, String externalId) {
        this.id = Long.valueOf(id);
        this.name = name;
        this.externalId = externalId;
    }

}
