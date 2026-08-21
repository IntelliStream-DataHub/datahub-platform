// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.models;

import ai.intellistream.datahub.helpers.text.TextValidator;
import ai.intellistream.datahub.json.ToStringSerializer;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import tools.jackson.databind.annotation.JsonSerialize;

/**
 * A single node related to some other node: the target's id/externalId (inherited from {@link IdCollection})
 * plus the {@code relationshipType} and {@link RelationDirection} describing how they are related. The
 * element type of a node's {@code relatedResources} (and the legacy {@code Timeseries.relationsFrom}).
 */
@EqualsAndHashCode(callSuper = true)
@Data
@Schema(name = "RelatedNode", description = "A node related to another, with relationship type and direction.")
public class RelatedNode extends IdCollection {

    @Schema(description = "The relationship type of the object,", example = "publishes_data_to", required = false)
    private String relationshipType;

    /**
     * Direction of the relationship relative to the node this entry is attached to. Populated on outbound
     * {@code relatedResources} lists; left null on input payloads and on legacy inbound-only representations.
     */
    @Schema(description = "Direction of the relation relative to the node it is attached to.", example = "OUTBOUND", required = false)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private RelationDirection direction;

    /**
     * Id of the edge that realizes this relation. Lets a client fetch the full edge (e.g. its metadata) by
     * id without inflating every relation with edge detail. Populated on outbound lists; null on input.
     */
    @Schema(description = "Id of the edge realizing this relation; look up the edge by this id for its detail/metadata.", example = "98231", required = false)
    @JsonSerialize(using = ToStringSerializer.class)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Long edgeId;

    public RelatedNode() {
        super();
    }

    public static RelatedNode createFromId(long id) {
        var instance = new RelatedNode();
        instance.setId(id);
        return instance;
    }

    public static RelatedNode createFromExternalId(String id) {
        var instance = new RelatedNode();
        instance.setExternalId(id);
        return instance;
    }

    public void setRelationshipType(String relationshipType) {
        this.relationshipType = TextValidator.toSnakeUpperCased(relationshipType);
    }

}
