// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.models;

import ai.intellistream.datahub.helpers.text.ExternalIds;
import ai.intellistream.datahub.json.ToStringSerializer;
import ai.intellistream.datahub.models.validation.ForbiddenValues;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;
import tools.jackson.databind.annotation.JsonSerialize;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Shared base for the outbound node DTOs ({@code Resource}, {@code Timeseries}, {@code DataSetModel},
 * {@code Policy}) — the primitives that <em>every</em> node type legally has, so each type gets them once
 * instead of re-declaring. Type-restricted fields (e.g. {@code isRoot}, {@code valueType}, policy
 * {@code type}) stay on the subclasses, which keeps illegal combinations unrepresentable. See
 * {@code NODE_READ_REFACTOR.md}.
 *
 * <p>Hoisted so far: {@code id}, {@code externalId}, {@code name}, {@code description}, {@code metadata},
 * {@code source}, {@code labels}. Every node is typed by its labels (the type-label picks the concrete
 * entity on create), so carrying them on the base is what lets a single polymorphic create request hold
 * a mix of node types. {@code dataSetId}/timestamps are not yet uniform across the DTOs and are reconciled
 * in later slices.
 *
 * <p>{@code externalId}/{@code name} validation is unified here (previously it diverged per type); the base
 * carries {@code @NotBlank @Size} + {@code @ForbiddenValues}, and canonicalizes {@code externalId} to
 * snake_case on set for <em>every</em> node type (the node table hashes the snake-cased form for lookups,
 * so this keeps the returned id consistent with what's stored/queried).
 */
@Getter
@Setter
public abstract class NodeModel extends AbstractResource {

    @JsonSerialize(using = ToStringSerializer.class)
    @Schema(description = "The id of the object.", example = "5677892")
    private Long id;

    @NotBlank
    @Size(min = 3, max = 256)
    @ForbiddenValues(message = "Invalid value for external id.")
    @Schema(description = "The external id of the object.", example = "klp_pipe_ws_a1212_dl")
    private String externalId;

    @NotBlank
    @Size(min = 3, max = 512)
    @Schema(description = "The name of the object.", example = "klp pipe ws-a1212-dl")
    private String name;

    @Schema(description = "Entity specific metadata. A key-value store.", example = "{\"work_order\": \"wo-sap-12344\"}")
    private Map<String, String> metadata = new HashMap<>();

    @Schema(description = "The description of the object.", example = "Water stream pipe")
    private String description;

    /**
     * The name of the system that holds the primary information about this node — the upstream system of
     * record it was ingested from (SAP, a historian, a file drop). Universal: every node type can legally
     * originate outside the platform, and the storage layer already treats it as a shared node column
     * ({@code node.source}), so it belongs on the base rather than only on {@code Resource}.
     */
    @Pattern(regexp = "^$|.{2,128}", message = "pattern.resource.source.error")
    @Schema(description = "The name of the data source containing the primary information about the object.", example = "dolphin_rex_pipes")
    private String source;

    /**
     * The data set this node belongs to.
     *
     * <p>Universal: every node type can sit in a data set, and the storage layer already treats it
     * as a shared node column. It was declared — identically, right down to the serializer — on
     * {@code Resource}, {@code Timeseries} and {@code Policy}, three copies free to drift apart.
     *
     * <p>{@code NON_NULL} so a node with no data set simply omits the field rather than emitting
     * {@code null}. That is what {@code Resource} already did through its class-level policy, and it
     * keeps the field from appearing on {@code DataSetModel}, which expresses its own parentage
     * through {@code connectedDataSets} instead (a dataset sits in a DAG of datasets, not in one).
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonSerialize(using = ToStringSerializer.class)
    @Schema(description = "The id of the data set this node belongs to.", example = "12")
    private Long dataSetId;

    /**
     * The labels associated with this node. A node's type-label (one of ASSET/DATASET/POLICY/TIMESERIES/
     * FUNCTION) lives here and is the signal the create pipeline reads to pick the concrete entity;
     * additional free-form labels may accompany it. Universal — every node type carries labels — which is
     * what lets a single {@code /resources/create} request hold a mix of types (e.g. a timeseries next to
     * an asset). Type-bearing subclasses default this to their own type-label so a body need not repeat it.
     */
    @NotNull
    @Size(min = 1, message = "resource.needs.at.least.one.label")
    @Schema(description = "A list of the labels associated with this node.", example = "[\"resource\", \"PIPE\"]")
    private List<String> labels = new ArrayList<>();

    /**
     * The unified node-centric relation encoding: the nodes this one is connected to, each with its
     * id/externalId, the relationship type, and the {@link RelationDirection}. Replaces the divergent
     * per-type encodings (Resource's edge-centric {@code relations}, Timeseries' inbound-only
     * {@code relationsFrom}). Populated where the graph is loaded (see {@code ResourceNetwork}); empty
     * otherwise.
     */
    @Schema(description = "Nodes this node is connected to, with relationship type and direction.",
            example = "[{\"id\": 34, \"externalId\": \"sensor_abc\", \"relationshipType\": \"PUBLISHES_DATA_TO\", \"direction\": \"OUTBOUND\"}]")
    private List<RelatedNode> relatedResources = new ArrayList<>();

    /**
     * The label that types this node, or {@code null} for types whose type comes from the caller's
     * label set rather than from the class ({@code Resource}/{@code Asset}).
     *
     * <p>Overriding this is the whole of what a self-typing node type has to declare.
     * {@link #setLabels(List)} then guarantees the label is present however the list was supplied.
     */
    protected String typeLabel() {
        return null;
    }

    /**
     * Set the labels, always keeping {@link #typeLabel()} present.
     *
     * <p>Replaces two different mechanisms that used to coexist: {@code Timeseries},
     * {@code DataSetModel} and {@code Policy} seeded the type-label in a constructor — which
     * Jackson then <em>overwrote</em> the moment a request body carried its own {@code labels},
     * silently dropping the type — while {@code Function} overrode {@code setLabels} to append it.
     * The append behaviour was the correct one; it now lives here for every node type, and
     * caller-supplied labels survive alongside the type instead of competing with it.
     * (Deduplication beyond the type-label is left to label resolution.)
     */
    public void setLabels(List<String> labels) {
        var next = labels == null ? new ArrayList<String>() : new ArrayList<>(labels);
        String type = typeLabel();
        if (type != null && !next.contains(type)) {
            next.add(type);
        }
        this.labels = next;
    }

    /**
     * Stores the external id verbatim — no canonicalization, uniform across every node type.
     *
     * <p>This used to snake_case the value, which meant a caller mirroring the plant tag
     * {@code COM-99-PT-1034} read back {@code com_99_pt_1034} and every byte-equality join against
     * the source system quietly stopped matching. Case-insensitivity now lives in the hash
     * ({@link ai.intellistream.datahub.helpers.text.ExternalIds#hash}), which is where it can serve
     * uniqueness and lookup without destroying what was sent.
     */
    public void setExternalId(String externalId) {
        this.externalId = externalId;
    }

    // Hand-written (not Lombok @EqualsAndHashCode) so subclasses inherit identity-by-(id, externalId) without
    // each re-declaring `of = {"id","externalId"}` on fields that now live in this base. getClass() keeps it
    // per-type: a Resource and a Timeseries with the same id are never equal.
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        NodeModel that = (NodeModel) o;
        return Objects.equals(id, that.id) && Objects.equals(externalId, that.externalId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(getClass(), id, externalId);
    }
}
