// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.docs;

import ai.intellistream.datahub.jpa.domains.RelationshipType;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import tools.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper;

import java.util.ArrayList;
import java.util.Collection;

/**
 * Documentation-only view of the relationship-type endpoints' response.
 *
 * <p>Those endpoints return {@code ResponseEntity<?>}, which erases the payload type and leaves
 * OpenAPI describing the response as an empty object. This class exists so the generated schema says
 * what actually comes back. It is never instantiated or returned — the real payload is a
 * {@code DataWrapper<RelationshipType>}, and the two must be kept in step by hand.
 */
@Schema(name = "Relationship Type Collection",
        description = "Data response with a collection of relationship types.")
public class RelationshipTypeDataWrapper {

    @JacksonXmlElementWrapper(useWrapping = false)
    private Collection<RelationshipType> items = new ArrayList<>();

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public Collection<RelationshipType> getItems() {
        return items;
    }
}
