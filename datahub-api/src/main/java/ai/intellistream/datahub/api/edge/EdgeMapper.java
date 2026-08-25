// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.edge;

import ai.intellistream.datahub.api.controllers.errors.BadRequestError;
import ai.intellistream.datahub.api.controllers.errors.BadRequestException;
import ai.intellistream.datahub.api.datasecurity.DatasetClosureService;
import ai.intellistream.datahub.errors.ObjectNotFoundException;
import ai.intellistream.datahub.errors.ResponseError;
import ai.intellistream.datahub.helpers.text.ExternalIds;
import ai.intellistream.datahub.jpa.domains.EdgeEntity;
import ai.intellistream.datahub.jpa.domains.NodeType;
import ai.intellistream.datahub.jpa.domains.RelationshipType;
import ai.intellistream.datahub.jpa.dto.EdgeEndpoint;
import ai.intellistream.datahub.jpa.dto.NameAndExternalIdDTO;
import ai.intellistream.datahub.models.RelForm;
import ai.intellistream.datahub.repositories.node.NodeRepository;
import ai.intellistream.datahub.repositories.node.RelationshipTypeRepository;
import ai.intellistream.datahub.services.RelationshipTypeService;
import jakarta.validation.Valid;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

/**
 * Maps a {@link RelForm} onto an {@link EdgeEntity} — resolving both endpoints (by id or
 * external-id hash) and the relationship type (by id, or race-safe find-or-create by name) — and
 * owns the what-may-connect-to-what rules. Extracted from {@code ResourceService} so the
 * resource, timeseries, policy and (future) unified create paths can all build edges through one
 * component without depending on each other; {@code ResourceService} keeps a thin delegating
 * {@code mapEdge} for its existing callers.
 */
@Component
public class EdgeMapper {

    private final NodeRepository nodeRepository;
    private final RelationshipTypeRepository relationshipTypeRepository;
    private final RelationshipTypeService relationshipTypeService;

    public EdgeMapper(NodeRepository nodeRepository,
                      RelationshipTypeRepository relationshipTypeRepository,
                      RelationshipTypeService relationshipTypeService) {
        this.nodeRepository = nodeRepository;
        this.relationshipTypeRepository = relationshipTypeRepository;
        this.relationshipTypeService = relationshipTypeService;
    }

    @Transactional
    public EdgeEntity mapEdge(EdgeEntity edge, @Valid RelForm form) {

        NameAndExternalIdDTO fromNode = null;
        try{
            if(form.getFromId() != null){
                fromNode = nodeRepository.findById(form.getFromId(), NameAndExternalIdDTO.class)
                        .orElseThrow(() -> new ObjectNotFoundException("Source node not found with id: " + form.getFromId()));
            } else if(form.getFromExternalId() != null){
                long id = ExternalIds.hash(form.getFromExternalId());
                fromNode = nodeRepository.findByExternalIdHash(id, NameAndExternalIdDTO.class);
            }
        } catch (EmptyResultDataAccessException e){
            throw endpointNotFound("fromNode", form.getFromExternalId(), form.getFromId());
        }
        // A lookup by external id answers with null rather than throwing, and a form naming
        // neither an id nor an external id never looks anything up at all. Both left the edge
        // with a null endpoint, which surfaced as a bare "must not be null" from the entity's
        // @NotNull at flush time — so say plainly which endpoint could not be resolved.
        if (fromNode == null) {
            throw endpointNotFound("fromNode", form.getFromExternalId(), form.getFromId());
        }

        NameAndExternalIdDTO toNode = null;
        try{
            if(form.getToId() != null){
                toNode = nodeRepository.findById(form.getToId(), NameAndExternalIdDTO.class)
                        .orElseThrow(() -> new ObjectNotFoundException("Target node not found with id: " + form.getToId()));
            } else if(form.getToExternalId() != null){
                long id = ExternalIds.hash(form.getToExternalId());
                toNode = nodeRepository.findByExternalIdHash(id, NameAndExternalIdDTO.class);
            }
        } catch (EmptyResultDataAccessException e){
            throw endpointNotFound("toNode", form.getToExternalId(), form.getToId());
        }
        if (toNode == null) {
            throw endpointNotFound("toNode", form.getToExternalId(), form.getToId());
        }

        edge.setStart(fromNode.getId());
        edge.setEnd(toNode.getId());
        edge.setDescription(form.getDescription());

        RelationshipType relType = null;
        if(form.getRelationshipTypeId() != null){
            relType = relationshipTypeRepository.getReferenceById(form.getRelationshipTypeId());
        }
        // Fall through to find-or-create when the caller supplied a name — delegating to
        // RelationshipTypeService so concurrent edge creates naming the same new
        // relationship can't collide on relationship_hash_key.
        if(relType == null && form.getRelationshipType() != null){
            relType = relationshipTypeService.findOrCreateByName(form.getRelationshipType());
        }
        edge.setRelationshipType(relType);

        edge.setMetadata(form.getMetadata());

        if(form.getDataSetId() != null){
            edge.setDataSet(nodeRepository.getReferenceById(form.getDataSetId()));
        }

        assertEdgeEndpointsAllowed(edge);
        return edge;
    }

    /**
     * A 400 naming the endpoint that could not be resolved, and what was submitted for it.
     *
     * <p>{@code String.valueOf} rather than the raw values because the fields map is
     * {@code Map<String, String>}, which rejects nulls — and a null is exactly what a caller who
     * supplied neither identifier needs to see reported back.
     */
    private static BadRequestException endpointNotFound(String endpoint, String externalId, Long id) {
        ResponseError<BadRequestError> errors = new ResponseError<>();
        BadRequestError error = new BadRequestError();
        error.setMessage("Could not find " + endpoint);
        error.getFields().add(Map.of(
                "externalId", String.valueOf(externalId),
                "id", String.valueOf(id)));
        errors.setError(error);
        return new BadRequestException(errors);
    }

    /**
     * What may connect to what. Mirrors the console's edge-form guard, but here it is the
     * enforcement point — a direct API call must obey the same rules:
     * <ul>
     *   <li>A relation TO a dataset is how something becomes part of it, and membership is
     *       {@code BELONGS_TO} — any other type would read as structure yet mean nothing to the
     *       hierarchy (ACL closure, dataset timeseries listing), so it is rejected.</li>
     *   <li>A timeseries has one dataset. A dataset may connect to a timeseries only when the
     *       series has no dataset yet or already belongs to <em>that</em> dataset — the equal case
     *       must stay legal because creating a timeseries inside a dataset creates exactly that
     *       membership edge in the same request.</li>
     * </ul>
     * Runs against the edge's final endpoints and type, so {@link #mapEdge} and
     * {@link #updateEdge} (which can retarget an edge) share it.
     */
    public void assertEdgeEndpointsAllowed(EdgeEntity edge) {
        if (edge.getStart() == null || edge.getEnd() == null) {
            return;
        }
        EdgeEndpoint to = nodeRepository.findById(edge.getEnd(), EdgeEndpoint.class).orElse(null);
        if (to == null) {
            return;
        }
        String relName = edge.getRelationshipType() == null ? null : edge.getRelationshipType().getName();
        long toType = nodeTypeOf(to);
        if (toType == NodeType.DATASET && !DatasetClosureService.BELONGS_TO.equalsIgnoreCase(relName)) {
            throw edgeRuleViolation("A relation to a data set must use the BELONGS_TO relationship");
        }
        if (toType == NodeType.TIMESERIES) {
            EdgeEndpoint from = nodeRepository.findById(edge.getStart(), EdgeEndpoint.class).orElse(null);
            Long tsDataSet = (to.getDataSet() == null) ? null : to.getDataSet().getId();
            if (from != null && nodeTypeOf(from) == NodeType.DATASET
                    && tsDataSet != null && !tsDataSet.equals(edge.getStart())) {
                throw edgeRuleViolation(
                        "The time series already belongs to a data set and cannot be connected to another one");
            }
        }
    }

    private static long nodeTypeOf(EdgeEndpoint endpoint) {
        return (endpoint.getNodeType() == null || endpoint.getNodeType().getId() == null)
                ? -1 : endpoint.getNodeType().getId();
    }

    private static BadRequestException edgeRuleViolation(String message) {
        ResponseError<BadRequestError> errors = new ResponseError<>();
        BadRequestError error = new BadRequestError();
        error.setMessage(message);
        errors.setError(error);
        return new BadRequestException(errors);
    }
}
