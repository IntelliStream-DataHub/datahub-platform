// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.function;

import com.fasterxml.jackson.annotation.JsonInclude;
import ai.intellistream.datahub.models.NodeModel;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

/**
 * API-facing representation of a {@code FunctionEntity}. A Function is a plain datastore
 * node distinguished only by the canonical {@code FUNCTION} type-label, and it supports the
 * full create/read/update/delete surface a resource does — its writes run through the same
 * {@code ResourceService} pipeline.
 * <p>
 * Extends {@link NodeModel}, the shared node base: {@code id}, {@code externalId},
 * {@code name}, {@code description}, {@code metadata}, {@code source}, {@code dataSetId},
 * {@code labels} and {@code relatedResources}. It deliberately does <em>not</em> extend
 * {@code Resource}, which would hand it {@code isRoot}, {@code geoLocation},
 * {@code valueType} and {@code elementId} — four fields a function has no meaning for. The
 * base exists so a node type carries only what is legal for it; sharing the write pipeline
 * needs the node shape, not the resource shape.
 * <p>
 * {@link NodeModel#setLabels(List)} keeps the canonical {@code FUNCTION} label present — see
 * {@link #typeLabel()} — so users can attach additional domain labels alongside it without
 * repeating the type tag.
 */
@Getter
@Setter
@NoArgsConstructor
@JsonPropertyOrder({"id", "externalId", "name", "labels", "createdTime", "lastUpdatedTime"})
@Schema(name = "Function", description = "Function datastore node")
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Function extends NodeModel {

    public static final String CANONICAL_LABEL = "FUNCTION";

    {
        // Run after NodeModel's field initialiser (which gives an empty list) so a
        // freshly-constructed Function — including the no-args call Jackson uses
        // before populating fields — already carries the canonical label.
        setLabels(new ArrayList<>());
    }

    @Override
    protected String typeLabel() {
        return CANONICAL_LABEL;
    }
}
