// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.models;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.util.ArrayList;

/**
 * An asset: the node type that carries a geographic location. Split out of {@link Resource} so
 * {@code geoLocation} exists only where it is legal ({@code AssetEntity} is the only entity with
 * the column) and mixed-type reads can type assets precisely. Distinguished by the canonical
 * {@code ASSET} type-label, which {@link NodeModel#setLabels} keeps present.
 */
@Schema(name = "Asset", description = "Asset node: a resource with a geographic location")
@Getter
@Setter
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({"id", "externalId", "name", "*"})
@ToString(callSuper = true)
public class Asset extends NodeModel {

    public Asset() {
        // Seeded through the shared setter; NodeModel applies the type-label (see typeLabel()).
        setLabels(new ArrayList<>());
    }

    @Override
    protected String typeLabel() {
        return "ASSET";
    }

    /**
     * Asset root, the starting point for navigating resource relations.
     */
    @Schema(description = "Is this a root resource?", example = "true")
    private Boolean isRoot = false;

    /**
     * Geographic data.
     */
    @Valid
    private GeoLocation geoLocation;
}
