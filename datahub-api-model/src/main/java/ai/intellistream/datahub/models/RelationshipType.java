// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.models;

import ai.intellistream.datahub.json.ToStringSerializer;
import tools.jackson.databind.annotation.JsonSerialize;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

import java.io.Serial;
import java.io.Serializable;

/**
 * Wire representation of a relationship type (edge type) — {@code FLOWS_TO}, {@code PART_OF},
 * {@code BELONGS_TO}, etc. The server-side type is a JPA entity that lives in {@code datahub-infra};
 * this is the framework-free contract shape the {@code /edges/types} endpoints serialize, so lean
 * clients (the Java SDK) can read it without the persistence layer.
 *
 * <p>Only the fields that cross the wire are modelled: the server hides {@code hash},
 * {@code dateCreated} and {@code lastUpdated}.
 */
@Getter
@Setter
@Schema(name = "RelationshipType", description = "A relationship (edge) type")
public class RelationshipType implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** Server-assigned id. Serialized as a JSON string so 64-bit values survive JS clients. */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    /** Normalised uppercase snake-case name, e.g. {@code FLOWS_TO}. */
    private String name;

    private String description;

    /** Optional i18n message code for a display label. */
    private String i18nCode;
}
