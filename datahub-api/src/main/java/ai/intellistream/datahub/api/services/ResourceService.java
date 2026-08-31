// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.services;

import ai.intellistream.datahub.api.policy.PolicyCandidate;
import ai.intellistream.datahub.api.policy.PolicyEnforcement;
import ai.intellistream.datahub.helpers.text.ExternalIds;
import ai.intellistream.datahub.models.policy.PolicyFinding;
import ai.intellistream.datahub.models.policy.PolicyWarning;
import ai.intellistream.datahub.api.controllers.errors.BadRequestError;
import ai.intellistream.datahub.api.controllers.errors.BadRequestException;
import ai.intellistream.datahub.api.datasecurity.DataSecurity;
import ai.intellistream.datahub.api.datasecurity.DatasetClosureService;
import ai.intellistream.datahub.api.messaging.events.DatasetAclInvalidationEvent;
import ai.intellistream.datahub.api.messaging.outbox.GraphOutbox;
import ai.intellistream.datahub.services.graph.GraphSyncCommand;
import ai.intellistream.datahub.errors.ObjectNotFoundException;
import ai.intellistream.datahub.api.controllers.errors.ResourceDeleteException;
import ai.intellistream.datahub.api.responses.DataWrapper;
import ai.intellistream.datahub.api.responses.GraphDataWrapper;
import ai.intellistream.datahub.asset.ResourceNetwork;
import ai.intellistream.datahub.errors.InvalidResourceException;
import ai.intellistream.datahub.errors.ResponseError;
import ai.intellistream.datahub.jpa.domains.*;
// Disambiguate from the api-model wire type ai.intellistream.datahub.models.RelationshipType,
// also on this file's wildcard imports: here RelationshipType is the JPA entity.
import ai.intellistream.datahub.jpa.domains.RelationshipType;
import ai.intellistream.datahub.jpa.dto.EdgeDTO;
import ai.intellistream.datahub.jpa.dto.EdgeEndpoint;
import ai.intellistream.datahub.jpa.dto.NameAndExternalId;
import ai.intellistream.datahub.jpa.dto.NameAndExternalIdDTO;
import ai.intellistream.datahub.models.*;
import ai.intellistream.datahub.models.datafilters.TimeFilter;
import ai.intellistream.datahub.models.datafilters.ResourceFilter;
import ai.intellistream.datahub.models.validation.ResourceFields;
import ai.intellistream.datahub.pulsar.EventAction;
import ai.intellistream.datahub.pulsar.EventObject;
import ai.intellistream.datahub.repositories.node.*;
import ai.intellistream.datahub.repositories.subscription.SubscriptionRepository;
import ai.intellistream.datahub.services.LabelService;
import ai.intellistream.datahub.services.Neo4JService;
import ai.intellistream.datahub.services.GraphConnectivityValidator;
import ai.intellistream.datahub.services.NodeService;
import ai.intellistream.datahub.services.RelationshipTypeService;
import ai.intellistream.datahub.tenant.TenantContext;
import ai.intellistream.datahub.transformers.EdgeProxyTransformer;
import ai.intellistream.datahub.transformers.ResourceTransformer;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.TypedQuery;
import ai.intellistream.datahub.models.paging.PageCursor;
import ai.intellistream.datahub.repositories.node.NodeSort;
import ai.intellistream.datahub.repositories.node.NodePredicateBuilder;
import ai.intellistream.datahub.jpa.domains.NodeType;
import ai.intellistream.datahub.models.IdCollection;
import jakarta.persistence.criteria.*;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Valid;
import jakarta.validation.Validator;
import lombok.extern.slf4j.Slf4j;
import org.apache.pulsar.client.api.PulsarClientException;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZonedDateTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
@Slf4j
public class ResourceService {

    private final DataSetRepository dataSetRepository;
    // No per-type repositories here any more. They existed only for the search fan-out that ran one
    // full-text query per node type; the search is a single generic node query now, so the four
    // typed repositories it needed are no longer dependencies of this service.
    @PersistenceContext
    private EntityManager entityManager;

    private final NodeRepository nodeRepository;

    private final NodeService nodeService;
    private final LabelService labelService;
    private final EdgeRepository edgeRepository;
    private final RelationshipTypeRepository relationshipTypeRepository;
    private final RelationshipTypeService relationshipTypeService;

    private final ApplicationEventPublisher applicationEventPublisher;
    private final GraphOutbox graphOutbox;

    private final Neo4JService neo4JService;

    private final DataSecurity dataSecurity;

    private final SubscriptionRepository subscriptionRepository;

    private final Validator validator;

    /** The naming policy, applied to every create and update. See {@link PolicyEnforcement}. */
    private final PolicyEnforcement policyEnforcement;

    /** The one authority for "which data sets are beneath this one" — shared with the ACL. */
    private final DatasetClosureService datasetClosureService;

    public ResourceService(
            EntityManager entityManager,
            NodeRepository nodeRepository,
            NodeService nodeService,
            LabelService labelService,
            EdgeRepository edgeRepository,
            RelationshipTypeRepository relationshipTypeRepository,
            RelationshipTypeService relationshipTypeService,
            ApplicationEventPublisher applicationEventPublisher,
            GraphOutbox graphOutbox,
            Neo4JService neo4JService,
            DataSetRepository dataSetRepository,
            DataSecurity dataSecurity,
            SubscriptionRepository subscriptionRepository,
            Validator validator,
            PolicyEnforcement policyEnforcement,
            DatasetClosureService datasetClosureService){
        this.entityManager = entityManager;
        this.nodeRepository = nodeRepository;
        this.nodeService = nodeService;
        this.labelService = labelService;
        this.edgeRepository = edgeRepository;
        this.relationshipTypeRepository = relationshipTypeRepository;
        this.relationshipTypeService = relationshipTypeService;
        this.applicationEventPublisher = applicationEventPublisher;
        this.graphOutbox = graphOutbox;
        this.neo4JService = neo4JService;
        this.dataSetRepository = dataSetRepository;
        this.dataSecurity = dataSecurity;
        this.subscriptionRepository = subscriptionRepository;
        this.validator = validator;
        this.policyEnforcement = policyEnforcement;
        this.datasetClosureService = datasetClosureService;
    }

    /**
     * Save assets to Postgres first, if everything goes well, submit assets
     * for further consumption in pulsar. Neo4j is a listener.
     *
     * @param apiReqData
     * @return DataWrapper<Asset>
     * @throws PulsarClientException
     * @throws RuntimeException
     */
    @Transactional(rollbackFor = Exception.class)
    public GraphDataWrapper<Resource, EdgeProxy> create(GraphDataWrapper<Resource, RelForm> apiReqData)
            throws PulsarClientException, RuntimeException {

        // Validate here too, not only at the controller: the resource_create / dataset_create
        // MCP tools call this method directly and would otherwise bypass the Resource/RelForm
        // bean constraints (e.g. a blank externalId or name).
        Set<ConstraintViolation<GraphDataWrapper<Resource, RelForm>>> violations = validator.validate(apiReqData);
        if (!violations.isEmpty()) {
            throw new ConstraintViolationException(violations);
        }

        // Create error list that can return missing nodes to user
        ResponseError<BadRequestError> errors = new ResponseError<>();

        // Naming policy, over the WHOLE batch and before anything is mapped or persisted. Placed
        // here rather than in the controller so the MCP tools — which call this method directly —
        // are covered by the same check. A NOT_OK verdict throws, so a rejected batch creates
        // nothing at all rather than creating some rows and rolling them back.
        List<PolicyFinding> policyWarnings = policyEnforcement.check(namingCandidatesForCreate(apiReqData.getNodes()));

        try{
            GraphDataWrapper<Resource, EdgeProxy> collection = new GraphDataWrapper<>();
            List<NodeEntity> nodes = new ArrayList<>();
            List<EdgeEntity> edges = new ArrayList<>();

            // Loop asset submitted for creations and create AssetNode objects
            for(Resource resource : apiReqData.getNodes()){

                // Deny creating a resource in a dataset the caller can't write (null dataset →
                // requires the write-all grant).
                dataSecurity.assertCanWriteDataSet(resource.getDataSetId());

                // Do not set a random id, this is because you will not get the
                // performance benefit from temporal locality
                // See: https://www.cybertec-postgresql.com/en/unexpected-downsides-of-uuid-keys-in-postgresql/
                // long rdmId = IdGenerator.getRandomId();
                // node.setId(rdmId);

                // Map asset form into ResourceEntity / Node object
                try{
                    nodes.add( nodeService.createFromResource(resource) );
                } catch (InvalidResourceException e){
                    throw toBadRequest(e);
                }
            }

            // Save all new assets so they get id and verify all is good
            try{
                Iterable<NodeEntity> savedResources = nodeRepository.saveAll(nodes);
                nodeRepository.flush();

                // Loop relations submitted for creations and create AssedEdge objects
                for(RelForm form : apiReqData.getRelations()){
                    EdgeEntity edge = new EdgeEntity();
                    // Map asset form into AssetNode object
                    edges.add( mapEdge(edge, form) );
                }
                // A link mutates the graph at BOTH ends, so authorise both endpoints — not just
                // the nodes[] submitted alongside, which is all the loop above checks. Nodes
                // created in this same request were already checked and are flushed by now, so
                // they resolve here too.
                assertCanWriteNodes(endpointIdsOf(edges));

                // Save all new edges so they get id and verify all is good
                Iterable<EdgeEntity> savedEdges = edgeRepository.saveAll(edges);

                // Convert saved objects into proxy objects and send to pulsar
                List<Resource> resourceList = new ArrayList<>();
                List<EdgeProxy> edgesList = new ArrayList<>();

                savedEdges.forEach( it -> edgesList.add( EdgeProxyTransformer.fromEdgeEntity(it) ));
                savedResources.forEach( it -> {
                    var a = ResourceTransformer.from(it, edgesList);
                    resourceList.add( a );
                });

                invalidateDatasetAclIfNeeded(nodes, edges, false);

                collection.setNodes(resourceList);
                collection.setRelations(edgesList);

                // Flush first: the outbox row records ids, and edge ids are assigned by the insert.
                entityManager.flush();
                graphOutbox.queueUpsert(nodes, edges);

                // Findings reference node_id, which only exists now. A rejected batch never gets
                // here, which is the intent: NOT_OK leaves no entity to attach a finding to.
                recordPolicyWarnings(policyWarnings, nodes);
                collection.setWarnings(policyWarnings.stream().map(PolicyWarning::from).toList());

                return collection;

            } catch (DataIntegrityViolationException dve){
                log.error(dve.getMessage(), dve);
                // Handle data integrity error
                Throwable cause = dve.getCause();
                if(cause instanceof org.hibernate.exception.ConstraintViolationException){
                    String constraintName = ((org.hibernate.exception.ConstraintViolationException) cause).getConstraintName();
                    if(constraintName != null && constraintName.equals("node_external_id_hash_key")){
                        var de = new BadRequestError();
                        de.setCode(409);
                        de.setMessage("External id already exists.");

                        // Regular expression to match the pattern
                        Pattern pattern = Pattern.compile("\\(([^)]+)\\)=\\(([^)]+)\\)");
                        Matcher matcher = pattern.matcher(cause.getMessage());
                        if (matcher.find()) {
                            long offendingValue = Long.parseLong(matcher.group(2));
                            nodes.stream()
                                    .filter( it -> it.getExternalIdHash() == offendingValue)
                                    .findFirst()
                                    .ifPresent( node -> describeExternalIdCollision(de, node.getExternalId(), offendingValue));
                        } else {
                            log.error("Could not parse the offending value.");
                        }
                        errors.setError(de);
                        throw new BadRequestException(errors);
                    }
                }
                // Other DataIntegrityViolationException — pass it back to the controller
                // with its original type so the caller can decide on a status. Bean-validation
                // failures (jakarta.validation.ConstraintViolationException) fall through this
                // catch entirely and propagate untouched, which lets the controller's typed
                // catch clauses produce a clean 400.
                throw dve;
            }

        } catch (BadRequestException e){
            throw e;
        }
    }

    /**
     * Reduce a create batch to what the naming policy needs to judge it.
     *
     * <p>Index is the item's position in the submitted batch, so a rejection can name which of 500
     * items was wrong rather than only that something was.
     */
    private static List<PolicyCandidate> namingCandidatesForCreate(Collection<Resource> resources) {
        List<PolicyCandidate> candidates = new ArrayList<>(resources.size());
        int index = 0;
        for (Resource resource : resources) {
            candidates.add(PolicyCandidate.forCreate(
                    index++, resource.getExternalId(), resource.getName(), resource.getDataSetId()));
        }
        return candidates;
    }

    /**
     * Reduce an update batch to naming-policy candidates, keeping only the items whose external id
     * actually changes.
     *
     * <p>Carrying the previous value is what makes that possible, and the rule it enables is not a
     * nicety: without it, tightening a policy makes every pre-existing resource unupdatable, and
     * editing an unrelated field on a legacy resource fails on a naming rule the caller never
     * touched. {@link PolicyCandidate#requiresEvaluation()} does the comparison.
     */
    private static List<PolicyCandidate> namingCandidatesForUpdate(
            List<Map.Entry<UpdateResourceForm, NodeEntity>> targets) {
        List<PolicyCandidate> candidates = new ArrayList<>();
        int index = 0;
        for (Map.Entry<UpdateResourceForm, NodeEntity> target : targets) {
            UpdateResourceForm form = target.getKey();
            NodeEntity entity = target.getValue();
            String newExternalId = form.getUpdate() == null ? null : form.getUpdate().getExternalId().getSet();
            if (newExternalId != null) {
                // The incoming name if the same request renames it, else the stored one — a
                // suggestion should be derived from the name the entity will actually have.
                String newName = form.getUpdate().getName().getSet();
                candidates.add(PolicyCandidate.forUpdate(
                        index, newExternalId,
                        newName != null ? newName : entity.getName(),
                        entity.getDataSet() == null ? null : entity.getDataSet().getId(),
                        entity.getId(), entity.getExternalId()));
            }
            index++;
        }
        return candidates;
    }

    /**
     * Persist warnings once the entities exist, mapping each finding's external id to its node id.
     *
     * <p>Keyed on external id rather than on batch position because the two do not line up: the
     * candidate list carries the submitted index, while {@code nodes} is what was actually mapped.
     */
    private void recordPolicyWarnings(List<PolicyFinding> warnings, List<NodeEntity> nodes) {
        if (warnings == null || warnings.isEmpty()) {
            return;
        }
        Map<String, PolicyEnforcement.WrittenEntity> writtenByExternalId = new HashMap<>();
        for (NodeEntity node : nodes) {
            if (node.getExternalId() != null && node.getId() != null) {
                writtenByExternalId.put(node.getExternalId(),
                        new PolicyEnforcement.WrittenEntity(node.getId(), dataSetIdOf(node)));
            }
        }
        policyEnforcement.recordWarnings(warnings, writtenByExternalId);
    }

    /**
     * The id of the node's data set, or null when it belongs to none.
     *
     * <p>Reads only the id off the lazy association, which the persistence context can answer from
     * the foreign key it already holds — touching any other field would fetch the whole dataset row
     * per node just to label a finding.
     */
    private static Long dataSetIdOf(NodeEntity node) {
        return node.getDataSet() == null ? null : node.getDataSet().getId();
    }

    /**
     * Fill in a duplicate-external-id error so it reads sensibly when the collision is one of case.
     *
     * <p>Without this the message is nonsense from the caller's point of view. External ids are
     * stored verbatim but made unique on their lowercased form, so creating {@code VAL-01} when
     * {@code val-01} exists is a duplicate — and a caller who has never sent {@code VAL-01} before
     * is told "external id already exists" about a string that appears nowhere in the system. So
     * look the existing row up by the hash the constraint rejected, and when it is not
     * byte-identical to what was submitted, say plainly that the two collide and name the one
     * already stored.
     */
    private void describeExternalIdCollision(BadRequestError error, String submittedExternalId, long collidingHash) {
        error.getFields().add(Map.of("externalId", submittedExternalId));

        // A separate read: the offending row is not in this transaction's flushed batch.
        NameAndExternalId existing = nodeRepository.findByExternalIdHash(collidingHash, NameAndExternalId.class);
        if (existing == null || existing.getExternalId() == null) {
            return;
        }
        error.getFields().add(Map.of("existingExternalId", existing.getExternalId()));
        if (!existing.getExternalId().equals(submittedExternalId)) {
            error.setMessage(
                    "External id '" + submittedExternalId + "' collides with the existing '"
                            + existing.getExternalId() + "'. External ids are stored exactly as sent "
                            + "but must be unique ignoring case, so these two cannot both exist.");
        }
    }

    /**
     * Update assets and relationships in Postgres first,
     * if everything goes well, submit assets for further action in pulsar.
     * Neo4j is a listener.
     *
     * @param apiReqData
     * @return DataWrapper<Asset>
     * @throws PulsarClientException
     * @throws BadRequestException
     * @throws RuntimeException
     */
    @Transactional(rollbackFor = Exception.class)
    public GraphDataWrapper<Resource, EdgeProxy> update(GraphDataWrapper<UpdateResourceForm, UpdateRelForm> apiReqData)
            throws PulsarClientException, RuntimeException {

        try{
            GraphDataWrapper<Resource, EdgeProxy> collection = new GraphDataWrapper<>();
            List<NodeEntity> nodes = new ArrayList<>();
            List<EdgeEntity> edges = new ArrayList<>();
            List<PolicyFinding> policyWarnings;

            // Create error list that can return missing nodes to user
            ResponseError<BadRequestError> errors = new ResponseError<>();

            // Resolve every target first, WITHOUT mutating any of them. The naming policy has to
            // see the whole batch before anything is written, and updateNode() below writes the new
            // external id straight onto the managed entity — after which Hibernate may auto-flush
            // ahead of the guard's own query and persist a value the policy was about to reject.
            // So: resolve, then judge, then apply.
            List<Map.Entry<UpdateResourceForm, NodeEntity>> targets = new ArrayList<>();
            for(UpdateResourceForm form : apiReqData.getNodes()){
                NodeEntity resource = null;
                Long id = form.getId();
                String externalId = form.getExternalId();
                if(id == null && externalId == null){
                    var de = new BadRequestError();
                    de.setMessage("Missing both id and externalId, asset cannot be found.");
                    de.getFields().add(Map.of("externalId", "null", "id", "null"));
                    errors.setError(de);
                    throw new BadRequestException(errors);
                } else if(id != null){
                    resource = nodeRepository.findById(id).orElse(null);
                } else {
                    Long externalHashId = ExternalIds.hash(externalId);
                    resource = nodeRepository.findByExternalIdHash(externalHashId);
                }

                if(resource != null){
                    // Must be able to write the resource's current dataset before mutating it.
                    dataSecurity.assertCanWrite(resource);
                    // Functions are plain datastore nodes now — editable like any resource.
                    targets.add(Map.entry(form, resource));
                } else {
                    var de = new BadRequestError();
                    de.setMessage("Resource cannot be found.");
                    de.getFields().add(Map.of("externalId", String.valueOf(externalId), "id", String.valueOf(id)));
                    errors.setError(de);
                    throw new BadRequestException(errors);
                }
            }

            // If missing nodes found, throw error to user
            if(errors.getError() != null && !errors.getError().getFields().isEmpty()){
                throw new BadRequestException(errors);
            }

            // Only ids that actually change are judged — see namingCandidatesForUpdate. Otherwise
            // tightening a policy would make every pre-existing resource unupdatable, and a steward
            // fixing a description would get a naming error on a field they did not touch.
            policyWarnings = policyEnforcement.check(namingCandidatesForUpdate(targets));

            // Now apply. Nothing above this line has mutated an entity.
            for (Map.Entry<UpdateResourceForm, NodeEntity> target : targets) {
                nodes.add( updateNode(target.getValue(), target.getKey()) );
            }

            // Update all new assets so they get id and verify all is good
            Iterable<NodeEntity> savedResources = nodeRepository.saveAll(nodes);

            // Loop relations submitted for creations and create Edge objects
            Set<Long> edgeEndpointIds = new HashSet<>();
            AtomicBoolean belongsToTouched = new AtomicBoolean();
            for(UpdateRelForm form : apiReqData.getRelations()){
                edgeRepository.findById(form.getId()).ifPresent(edge -> {
                    // updateEdge can re-point an edge (RelFields.start / .end), which mutates the
                    // graph at the OLD endpoints as well as the new ones. Snapshot the current
                    // endpoints before updateEdge overwrites them, or re-pointing an edge you can
                    // write onto a node you cannot would go unchecked.
                    edgeEndpointIds.add(edge.getStart());
                    edgeEndpointIds.add(edge.getEnd());
                    // Capture BELONGS_TO-ness before the update, or re-typing a hierarchy edge to
                    // something else would slip past the post-update check below.
                    if (isBelongsTo(edge)) belongsToTouched.set(true);
                    edges.add(updateEdge(edge, form));
                });
            }
            edgeEndpointIds.addAll(endpointIdsOf(edges));
            assertCanWriteNodes(edgeEndpointIds);

            // Save all new edges so they get id and verify all is good
            Iterable<EdgeEntity> savedEdges = edgeRepository.saveAll(edges);

            // Convert saved objects into proxy objects and send to pulsar
            List<Resource> resourceList = new ArrayList<>();
            List<EdgeProxy> edgesList = new ArrayList<>();
            savedResources.forEach( it -> {
                resourceList.add( ResourceTransformer.from(it) );
            });
            savedEdges.forEach( it -> edgesList.add( EdgeProxyTransformer.fromEdgeEntity(it) ));

            invalidateDatasetAclIfNeeded(nodes, edges, belongsToTouched.get());

            collection.setNodes(resourceList);
            collection.setRelations(edgesList);

            nodeRepository.flush();
            graphOutbox.queueUpsert(nodes, edges);

            recordPolicyWarnings(policyWarnings, nodes);
            collection.setWarnings(policyWarnings.stream().map(PolicyWarning::from).toList());

            return collection;
        } catch (BadRequestException e){
            throw new BadRequestException(e.getError());
        } catch (InvalidResourceException e){
            // e.g. an invalid label name reached label resolution — surface it as a 400, not a 500.
            throw toBadRequest(e);
        } catch (AccessDeniedException e){
            // Let the denial reach AccessDeniedExceptionHandler as a 403. The catch below would
            // otherwise re-wrap it and surface every denied update as a 500 — which was already
            // true of the assertCanWrite above, not just the edge-endpoint check.
            throw e;
        }  catch (RuntimeException e){
            throw new RuntimeException(e);
        }
    }

    /**
     * Convert an {@link InvalidResourceException} (thrown by lower layers — label resolution, node
     * typing, ...) into a {@link BadRequestException} so it surfaces as a 400 carrying the original
     * message, plus the offending field when the source set one.
     */
    private BadRequestException toBadRequest(InvalidResourceException e) {
        var source = e.getError().getError();
        var de = new BadRequestError();
        de.setMessage(source.getErrorMessage());
        if (source.getField() != null && !source.getField().isBlank()) {
            de.addFieldError(source.getField(), source.getErrorMessage());
        }
        var resp = new ResponseError<BadRequestError>();
        resp.setError(de);
        return new BadRequestException(resp);
    }

    @Transactional
    public EdgeEntity updateEdge(EdgeEntity edge, UpdateRelForm form) {
        ResponseError<BadRequestError> errors = new ResponseError<>();
        if(!form.getUpdate().validateFields()){
            errors.setError(new BadRequestError());
            form.getUpdate().getErrors().forEach( error -> {
                errors.getError().addFieldError(error.getObjectName(), error.getDefaultMessage());
            });
            throw new BadRequestException(errors);
        }

        RelFields fields = form.getUpdate();
        edge.setLastUpdated(ZonedDateTime.now());

        /**
         * Update metadata
         * If key found, update metadata value in existing entry,
         * If key not found, add entry
         * If remove, delete metadata entry
         */

        if(fields.getMetadata().getSet() != null) {
            edge.setMetadata(fields.getMetadata().getSet());
        }

        if(fields.getMetadata().getAdd() != null){
            var meta = edge.getMetadata();
            meta.putAll(fields.getMetadata().getAdd());
            edge.setMetadata(meta);
        }

        if(fields.getMetadata().getRemove() != null){
            var meta = edge.getMetadata();
            meta.keySet().removeAll(fields.getMetadata().getRemove());
            edge.setMetadata(meta);
        }

        // Update description field
        if(fields.getDescription().getSet() != null){
            edge.setDescription(fields.getDescription().getSet());
        }
        if(fields.getDescription().getSetNull()){
            edge.setDescription(null);
        }

        // Update RelationShip field. Delegates to RelationshipTypeService so concurrent
        // edge updates naming the same new relationship don't collide on relationship_hash_key.
        if(fields.getRelationship().getSet() != null){
            edge.setRelationshipType(
                    relationshipTypeService.findOrCreateByName(fields.getRelationship().getSet())
            );
        }
        if(fields.getRelationshipId().getSet() != null){
            long relId = fields.getRelationshipId().getSet();
            Optional<RelationshipType> relType = relationshipTypeRepository.findById(relId);
            if(relType.isEmpty()){
                RelationshipType rt = new RelationshipType();
                rt.setName(fields.getRelationship().getSet());
                relationshipTypeRepository.save(rt);
                relationshipTypeRepository.flush();
            } else {
                edge.setRelationshipType(relType.get());
            }
        }

        // Update edge from id
        if(fields.getStart().getSet() != null){
            edge.setStart(fields.getStart().getSet());
        }

        // Update edge to id
        if(fields.getEnd().getSet() != null){
            edge.setEnd(fields.getEnd().getSet());
        }

        // Same endpoint rules as create — an update can retarget the edge or change its type.
        assertEdgeEndpointsAllowed(edge);
        return edge;
    }

    @Transactional
    public NodeEntity updateNode(NodeEntity resource, UpdateResourceForm form) {
        ResponseError<BadRequestError> errors = new ResponseError<>();
        if(!form.getUpdate().validateFields()){
            errors.setError(new BadRequestError());
            form.getUpdate().getErrors().forEach( error -> {
                errors.getError().addFieldError(error.getObjectName(), error.getDefaultMessage());
            });
            throw new BadRequestException(errors);
        }

        ResourceFields fields = form.getUpdate();
        resource.setLastUpdated(ZonedDateTime.now());

        // Update externalId
        if(fields.getExternalId().getSet() != null){
            String newExternalId = fields.getExternalId().getSet();
            if(form.getExternalId() == null){
                form.setExternalId(resource.getExternalId());
            }
            resource.setExternalId(newExternalId);
        }

        // Update name field
        if(fields.getName().getSet() != null){
            resource.setName(fields.getName().getSet());
        }

        /**
         * Update metadata
         * If key found, update metadata value in existing entry,
         * If key not found, add entry
         * If remove, delete metadata entry
         */
        Map<String, String> metadata = resource.getMetadata();
        if(fields.getMetadata().getSet() != null){
            resource.setMetadata(fields.getMetadata().getSet());
        }

        if(fields.getMetadata().getAdd() != null){
            resource.getMetadata().putAll(fields.getMetadata().getAdd());
        }
        if(fields.getMetadata().getRemove() != null ){
            resource.getMetadata().keySet().removeAll(fields.getMetadata().getRemove());
        }

        // Update description field
        if(fields.getDescription().getSet() != null){
            resource.setDescription(fields.getDescription().getSet());
        }
        if(fields.getDescription().getSetNull()){
            resource.setDescription(null);
        }
        // Update source field (common to all node types)
        if(fields.getSource().getSet() != null){
            resource.setSource(fields.getSource().getSet());
        }
        if(fields.getSource().getSetNull() ){
            resource.setSource(null);
        }
        if (resource instanceof AssetEntity asset){
            if(fields.getGeoLocation().getSet() != null){
                asset.setGeoLocation(fields.getGeoLocation().getSet().getJson());
            }
            if(fields.getGeoLocation().getSetNull()){
                asset.setGeoLocation(null);
            }
        }


        // Update labels. A node's type-label (ASSET/DATASET/POLICY/TIMESERIES/FUNCTION) is intrinsic
        // and immutable — resolveLabelUpdate returns the new label names with this node's type-label
        // enforced (empty when no label change was requested; see LabelService#resolveLabelUpdate).
        // Apply the same names to both Postgres representations so they can't drift: the labels string
        // and the M2M labelEntities (the latter is what the label-in-use check reads via Label.nodes).
        labelService.resolveLabelUpdate(resource, fields.getLabels())
                .ifPresent(labelNames -> nodeService.applyLabelNames(resource, labelNames));

        // Update dataset id field
        if(fields.getDataSetId().getSet() != null){
            Long dataSetId = fields.getDataSetId().getSet();
            // Moving a resource into a dataset also requires write access to the target.
            dataSecurity.assertCanWriteDataSet(dataSetId);
            DatasetEntity dataSet = dataSetRepository.findById(dataSetId).orElseThrow(()->{
                var de = new BadRequestError();
                de.setMessage("DataSet cannot be found.");
                de.getFields().add(Map.of("DataSet.Id", String.valueOf(dataSetId)));
                errors.setError(de);
                return new BadRequestException(errors);
            });
            resource.setDataSet(dataSet);
        }
        if(fields.getDataSetId().getSetNull()){
            resource.setDataSet(null);
        }

        return resource;
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
    private void assertEdgeEndpointsAllowed(EdgeEntity edge) {
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


    // ---- dataset ACL cache invalidation -------------------------------------------------------

    /**
     * Fire {@link DatasetAclInvalidationEvent} if this mutation could change what a dataset grant
     * covers. Grants name datasets by {@code externalId} and expand down the {@code BELONGS_TO}
     * hierarchy, so the answer changes when a dataset appears, disappears or is renamed, or when a
     * hierarchy edge is added or removed.
     *
     * <p>Deliberately coarse: any dataset node touched, or any {@code BELONGS_TO} edge touched,
     * bumps the tenant's generation. Distinguishing a rename from a description edit would save a
     * recompute that costs one query, at the price of a subtler correctness argument. Datasets and
     * their hierarchy change rarely compared with the resources inside them, so the cache stays
     * effective. What matters is that ordinary resource writes do <em>not</em> bump it.
     */
    private void invalidateDatasetAclIfNeeded(Collection<? extends NodeEntity> nodes,
                                              Collection<EdgeEntity> edges,
                                              boolean touchedBelongsTo) {
        boolean datasetTouched = nodes != null && nodes.stream().anyMatch(n -> n instanceof DatasetEntity);
        if (!datasetTouched && !touchedBelongsTo && !containsBelongsTo(edges)) {
            return;
        }
        applicationEventPublisher.publishEvent(
                new DatasetAclInvalidationEvent(TenantContext.getTenantId()));
    }

    private static boolean containsBelongsTo(Collection<EdgeEntity> edges) {
        if (edges == null) return false;
        for (EdgeEntity e : edges) {
            if (isBelongsTo(e)) return true;
        }
        return false;
    }

    private static boolean isBelongsTo(EdgeEntity edge) {
        return edge != null
                && edge.getRelationshipType() != null
                && DatasetClosureService.BELONGS_TO.equalsIgnoreCase(edge.getRelationshipType().getName());
    }

    /** The non-null endpoint node ids of every given edge. */
    private static Set<Long> endpointIdsOf(Collection<EdgeEntity> edges) {
        Set<Long> ids = new HashSet<>();
        for (EdgeEntity e : edges) {
            if (e.getStart() != null) ids.add(e.getStart());
            if (e.getEnd() != null) ids.add(e.getEnd());
        }
        return ids;
    }

    /**
     * Deny the request unless the caller can write the dataset of <em>every</em> one of these
     * nodes. Used to authorise edge mutations by their endpoints, because an edge has no dataset
     * of its own to check against.
     *
     * <p>Requiring write on <em>both</em> endpoints (rather than one, or read on the other) is
     * what closes a privilege escalation created by read and write being independent grants: a
     * caller with write but no read on node D — the shape of an ingest service account — could
     * otherwise attach D beneath a dataset they <em>can</em> read and inherit read on D through
     * the {@code BELONGS_TO} hierarchy. It also stops a caller linking arbitrary resources into
     * a dataset they have no write access to at all.
     *
     * <p>Nodes with no dataset are orphans and, per the usual rule, writable only by a caller
     * holding an all-datasets write grant. Dataset nodes themselves have no {@code data_set_id},
     * so building a dataset hierarchy needs that same grant — which is already true of creating a
     * dataset at all, so this adds no new restriction there.
     */
    private void assertCanWriteNodes(Collection<Long> nodeIds) {
        Set<Long> ids = nodeIds.stream().filter(Objects::nonNull).collect(Collectors.toSet());
        if (ids.isEmpty()) return;
        nodeRepository.findAllByIdIn(ids, NodeEntity.class).forEach(dataSecurity::assertCanWrite);
    }

    @Transactional(readOnly = true)
    public DataWrapper<Resource> get(Long id) {
        NodeEntity node = nodeRepository.findById(id).orElseThrow(() ->
                new ObjectNotFoundException("Node with id: " + id + " Not found!"));

        // Hide existence: a resource the caller may not read must be indistinguishable from a
        // missing one, so report 404 rather than letting assertCanRead surface a 403.
        if (!dataSecurity.hasReadPermissionToDataSet(node)) {
            throw new ObjectNotFoundException("Node with id: " + id + " Not found!");
        }

        DataWrapper<Resource> data = new DataWrapper<>();
        data.getItems().add(ResourceTransformer.from(node));
        return data;
    }

    @Transactional(readOnly = true)
    public DataWrapper<Resource> filter(ResourceRetreiver apiReqData) {
        DataWrapper<Resource> data = new DataWrapper<>();
        if (apiReqData.getFilter() == null) {
            return data;
        }

        ResourceFilter filter = apiReqData.getFilter();
        NodeSort sort = NodeSort.resolve(apiReqData.getSort());
        PageCursor cursor = NodePaging.validated(apiReqData.getCursor(), sort);

        // Resolve the data set scope before building anything: both of these can end the request,
        // and there is no point assembling a query we are about to throw away.
        Set<Long> requestedDataSets = null;
        if (filter.getDataSetId() != null) {
            // A data set stands in for everything beneath it in the BELONGS_TO hierarchy, the same
            // way a grant on it does. This used to match the listed ids exactly, so filtering on a
            // parent returned nothing from its children even though the caller could read them —
            // and timeseries, given the same filter, answered differently.
            requestedDataSets = datasetClosureService.closureOfReferences(filter.getDataSetId());
            if (requestedDataSets.isEmpty()) {
                // The caller asked to be narrowed to data sets that resolve to nothing. Dropping
                // the predicate would widen the query to everything they can read.
                return data;
            }
        }
        Set<Long> readableDataSets = null;
        if (!dataSecurity.hasReadAccessToEverything()) {
            readableDataSets = dataSecurity.readableDataSetIds();
            if (readableDataSets.isEmpty()) {
                return data; // no readable datasets -> empty result
            }
        }

        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<NodeEntity> q = cb.createQuery(NodeEntity.class);
        Root<NodeEntity> root = q.from(NodeEntity.class);

        // This is the generic node query: no node-type restriction unless the caller asks for one.
        // Its siblings are typed — /datasets/filter and /timeseries/filter each pin a discriminator,
        // because a query that omits it under single-table inheritance returns rows of every type
        // and the transformer presents them as whatever was asked for. Here that breadth is the
        // point rather than an accident, so the type set is stated explicitly and every node
        // carries its type as a label for the caller to read back.
        Set<Long> nodeTypes = NodeType.idsForNames(filter.getNodeType());
        if (filter.getNodeType() != null && !filter.getNodeType().isEmpty() && nodeTypes.isEmpty()) {
            // Every name was unknown. They asked to be narrowed to those types, so match nothing
            // rather than widening back to all of them.
            return data;
        }
        List<Predicate> predicates = NodePredicateBuilder.build(cb, q, root, filter, nodeTypes);

        // What is left is what only a resource has.
        if (filter.getIsRoot() != null) {
            predicates.add(cb.equal(root.get("isRoot"), filter.getIsRoot()));
        }

        if (requestedDataSets != null) {
            predicates.add(NodePredicateBuilder.dataSetScope(root, requestedDataSets));
        }
        // Narrow to the caller's readable datasets in SQL. The implicit inner join on dataSet
        // also drops orphan nodes (no dataset), which a non-all reader can't see anyway. Applied as
        // a second predicate rather than intersected in Java, so the two restrictions stay legible.
        if (readableDataSets != null) {
            predicates.add(NodePredicateBuilder.dataSetScope(root, readableDataSets));
        }

        if (cursor != null) {
            predicates.add(NodePredicateBuilder.keyset(cb, root, sort, cursor));
        }

        q.select(root)
                // No DISTINCT: the metadata criterion is an EXISTS subquery rather than a join, so
                // nothing here multiplies rows any more.
                .where(predicates.toArray(new Predicate[0]))
                // This query had no ORDER BY at all, so with a limit it returned an arbitrary
                // subset and two identical requests could disagree about which rows those were —
                // the kind of result that looks like data changing underneath you rather than like
                // a missing clause.
                .orderBy(NodePredicateBuilder.orderBy(cb, root, sort));

        TypedQuery<NodeEntity> query = entityManager.createQuery(q);
        query.setMaxResults(apiReqData.getLimit());
        List<NodeEntity> nodes = query.getResultList();
        data.setItems(ResourceTransformer.from(nodes));
        data.setNextCursor(NodePaging.nextCursor(nodes, apiReqData.getLimit(), sort));

        return data;
    }

    @Transactional(rollbackFor = Exception.class)
    public GraphDataWrapper<Resource, EdgeProxy> delete(GraphDataWrapper<Resource, EdgeProxy> apiReqData) throws PulsarClientException {

        Set<Long> resourceIdList = new HashSet<>();
        Set<Long> externalIdList = new HashSet<>();

        for(Resource r : apiReqData.getNodes()){
            if(r.getId() != null){
                resourceIdList.add(r.getId());
            }
            else if(r.getExternalId() != null){
                externalIdList.add( ExternalIds.hash(r.getExternalId()) );
            }
        }
        nodeRepository.findAllByExternalIdHashIn(externalIdList.stream().toList(), NameAndExternalIdDTO.class).forEach( it -> {
            resourceIdList.add(it.getId());
            Optional<Resource> entry = apiReqData.getNodes().stream().filter( n -> {
                var externalId = ExternalIds.hash(n.getExternalId());
                if(it.getExternalIdHash() == externalId){
                    return true;
                }
                return false;
            }).findFirst();
            if(entry.isPresent()){
                Resource r = entry.get();
                if(r.getId() != null){
                    r.setId(it.getId());
                }
            }
        });

        // Lock the nodes before checking their edges. Postgres' FK check for an incoming
        // INSERT INTO edge takes FOR KEY SHARE on the referenced node, which conflicts
        // with FOR UPDATE — so any concurrent edge-create targeting one of these nodes
        // blocks here. Without this, the caller might see their delete fail at commit with
        // a confusing FK violation just because someone else added an edge during the request.
        if(!resourceIdList.isEmpty()){
            nodeRepository.lockByIdIn(resourceIdList);
        }

        // Authorisation and subscription guards are enforced here, in the shared pipeline, so they
        // hold no matter which entry point reached us — the resource endpoint, the policy delete, or
        // the timeseries delete. Nodes resolve regardless of subtype (a timeseries is a node row),
        // so a timeseries deleted via /resources/delete still gets the same checks the timeseries
        // endpoint applies.
        if(!resourceIdList.isEmpty()){
            // Deny the whole batch unless the caller can write every targeted node's dataset.
            List<NodeEntity> deletedEntities = nodeRepository.findAllById(resourceIdList);
            deletedEntities.forEach(dataSecurity::assertCanWrite);

            // Block deletion while any targeted node is still referenced by a subscription, so no
            // subscriber silently loses its feed. Only timeseries are ever referenced, so this is a
            // no-op for other node types.
            checkForSubscriptions(resourceIdList);
        }

        Set<Long> edgeIdList = apiReqData.getRelations()
                .stream()
                .map(EdgeProxy::getId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        // Edges named directly in the request (POST /edges/delete, or a relations[] array on
        // /resources/delete) carry no node of their own to authorise against, so they are checked
        // on their endpoints: removing a link mutates the graph at both ends. Until this check
        // existed, any edge id in the tenant could be deleted by any caller.
        //
        // Deliberately BEFORE the cascade below adds incident edges. Those are not re-checked
        // here: the node being deleted was already authorised above, and demanding write on the
        // far end too would block a legitimate delete of a node that merely links into a dataset
        // the caller cannot write.
        if(!edgeIdList.isEmpty()){
            assertCanWriteNodes(endpointIdsOf(
                    edgeRepository.findAllByIdIn(edgeIdList, EdgeEntity.class)));
        }

        // Deleting a node cascades to every relationship it touches, inbound and outbound. The
        // connectivity check below decides whether that cascade is allowed; here we just collect
        // all incident edges so they go with the node.
        List<EdgeDTO> edges = edgeRepository.findAllByStartInOrEndIn(resourceIdList, resourceIdList, EdgeDTO.class);
        for(var edge : edges){
            edgeIdList.add(edge.getId());
        }

        // Snapshot edges before deletion so the connectivity check has their start/end available.
        List<EdgeEntity> edgesBeingDeleted = edgeRepository.findAllByIdIn(edgeIdList, EdgeEntity.class);

        // Reject deletes that would disjoint a surviving node from the graph root. Anchor the
        // Neo4j component load on the deleted nodes plus the endpoints of every edge being removed,
        // so the fetched component covers every node whose connectivity could change. The check
        // reads the graph mirror, which is applied after commit from the outbox queue — see
        // fetchComponentForNodes for the eventual-consistency caveat.
        Set<Long> connectivityAnchors = new HashSet<>(resourceIdList);
        for (EdgeEntity e : edgesBeingDeleted) {
            if (e.getStart() != null) {
                connectivityAnchors.add(e.getStart());
            }
            if (e.getEnd() != null) {
                connectivityAnchors.add(e.getEnd());
            }
        }
        if (!connectivityAnchors.isEmpty()) {
            ResourceNetwork component = neo4JService.fetchComponentForNodes(connectivityAnchors);
            Set<Long> stranded = GraphConnectivityValidator.findStrandedNodes(component, resourceIdList, edgeIdList);
            if (!stranded.isEmpty()) {
                // Resolve external ids from the loaded component so the error is meaningful to the
                // caller (who works in external ids); fall back to the internal id if a node has none.
                Map<Long, String> externalIdById = new HashMap<>();
                for (Resource r : component.nodes()) {
                    if (r.getId() != null && r.getExternalId() != null) {
                        externalIdById.put(r.getId(), r.getExternalId());
                    }
                }
                // Sort by id for a stable, readable message/field order (stranded is an unordered HashSet).
                List<Long> strandedSorted = stranded.stream().sorted().toList();
                List<String> strandedExternalIds = strandedSorted.stream()
                        .map(id -> externalIdById.getOrDefault(id, String.valueOf(id)))
                        .toList();
                var err = new BadRequestError();
                err.setMessage("Deleting this selection would disconnect resource(s) " + strandedExternalIds
                        + " from the graph root. Include them in the deletion or keep a connecting path.");
                for (Long id : strandedSorted) {
                    err.getFields().add(Map.of(
                            "type", "strandedResource",
                            "externalId", externalIdById.getOrDefault(id, String.valueOf(id))
                    ));
                }
                var resp = new ResponseError<BadRequestError>();
                resp.setError(err);
                throw new ResourceDeleteException(resp);
            }
        }

        edgeRepository.deleteAllById(edgeIdList);

        nodeRepository.deleteAllById(resourceIdList);

        nodeRepository.flush();

        GraphDataWrapper<Resource, EdgeProxy> deletedCollection = new GraphDataWrapper<>();
        resourceIdList.forEach( id -> {
            Resource a = new Resource();
            a.setId(id);
            deletedCollection.getNodes().add(a);
        });
        edgeIdList.forEach( id -> {
            EdgeProxy ep = new EdgeProxy();
            ep.setId(id);
            deletedCollection.getRelations().add(ep);
        });

        invalidateDatasetAclIfNeeded(
                resourceIdList.isEmpty() ? List.of() : nodeRepository.findAllById(resourceIdList),
                edgesBeingDeleted, false);

        graphOutbox.queueDelete(
                apiReqData.getNodes().stream()
                        .map(it -> new GraphSyncCommand.NodeRef(it.getId(), it.getExternalId()))
                        .toList(),
                apiReqData.getRelations().stream().map(EdgeProxy::getId).toList());

        return deletedCollection;
    }

    /**
     * Ensures none of the nodes about to be deleted are still referenced by a subscription. Only
     * timeseries are ever referenced, so this is a no-op for other node types. Throws
     * {@link ResourceDeleteException} listing the blocking subscriptions so the caller can remove
     * them before retrying the delete.
     */
    private void checkForSubscriptions(Set<Long> resourceIdList) {
        if (resourceIdList.isEmpty()) return;

        List<SubscriptionEntity> subs = subscriptionRepository.findAllByTimeseriesIdIn(resourceIdList);
        if (subs.isEmpty()) return;

        var err = new BadRequestError();
        err.setMessage("Cannot delete resource(s) that are referenced by subscription(s). "
                + "Remove the subscriptions first.");
        for (SubscriptionEntity sub : subs) {
            for (TimeseriesEntity ts : sub.getTimeseries()) {
                if (ts.getId() != null && resourceIdList.contains(ts.getId())) {
                    err.getFields().add(Map.of(
                            "type", "subscription",
                            "subscriptionId", String.valueOf(sub.getId()),
                            "subscriptionExternalId", sub.getExternalId(),
                            "timeseriesId", String.valueOf(ts.getId())
                    ));
                }
            }
        }
        var resp = new ResponseError<BadRequestError>();
        resp.setError(err);
        throw new ResourceDeleteException(resp);
    }

    @Transactional(readOnly = true)
    public DataWrapper<Resource> findAllByIdAndExternalId(Set<Long> idList, Set<String> externalIdList) {
        // Narrow to the caller's readable datasets in SQL; unreadable nodes are simply omitted.
        List<NodeEntity> assetNodes;
        if (dataSecurity.hasReadAccessToEverything()) {
            assetNodes = nodeRepository.findAllByIdOrExternalId(idList, externalIdList);
        } else {
            Set<Long> allowed = dataSecurity.readableDataSetIds();
            assetNodes = allowed.isEmpty()
                    ? new ArrayList<>()
                    : nodeRepository.findAllByIdOrExternalIdAndDataSetIdIn(idList, externalIdList, allowed);
        }
        DataWrapper<Resource> data = new DataWrapper<>();
        data.setItems(ResourceTransformer.from(assetNodes));
        return data;
    }

    /**
     * Full-text search across every node type, optionally narrowed by a {@link ResourceFilter}.
     *
     * <p>The text counterpart of {@link #filter}, and now literally that query plus one predicate:
     * the phrase is ANDed with the caller's criteria, the node-type discriminator and the data-set
     * scope, so the planner sees the whole conjunction and {@code LIMIT} stops as soon as it has
     * enough rows.
     *
     * <p>Two shapes preceded this. It first ran five per-type native queries and concatenated them,
     * so each type got the caller's whole {@code limit} (a request for 50 could return 250),
     * results came back grouped by type, and policies were never searched. Then it ran one native
     * phrase query up to a 10 000-row ceiling and re-asked the filter query about those ids, which
     * cost a second round trip and, past the ceiling, silently dropped rows that matched both.
     */
    @Transactional(readOnly = true)
    public DataWrapper<Resource> search(SearchBody<ResourceFilter> searchForm) {
        var data = new DataWrapper<Resource>();
        ResourceFilter filter = searchForm.getFilter();

        // A list of only unknown type names resolves to empty. They asked to be narrowed to those
        // types, so match nothing rather than widening back to all of them - same reading as filter.
        Set<Long> nodeTypes = filter == null ? Set.of() : NodeType.idsForNames(filter.getNodeType());
        if (filter != null && filter.getNodeType() != null && !filter.getNodeType().isEmpty()
                && nodeTypes.isEmpty()) {
            return data;
        }

        // Null means "no data-set restriction" throughout; a read-all caller keeps it null unless
        // the filter names data sets, in which case those become the whole restriction.
        Set<Long> allowed = dataSecurity.hasReadAccessToEverything() ? null : dataSecurity.readableDataSetIds();
        if (allowed != null && allowed.isEmpty()) {
            return data; // no readable datasets -> nothing to return, and don't query
        }
        if (filter != null && filter.getDataSetId() != null) {
            // A data set stands in for everything beneath it in the BELONGS_TO hierarchy, the same
            // way a grant on it does - the closure /resources/filter applies to the same field.
            Set<Long> requested = datasetClosureService.closureOfReferences(filter.getDataSetId());
            allowed = allowed == null
                    ? requested
                    : requested.stream().filter(allowed::contains).collect(Collectors.toSet());
            if (allowed.isEmpty()) {
                // Narrowed to data sets that resolve to nothing they can read. Dropping the
                // predicate would widen the query back to everything.
                return data;
            }
        }

        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<NodeEntity> q = cb.createQuery(NodeEntity.class);
        Root<NodeEntity> root = q.from(NodeEntity.class);

        List<Predicate> predicates = NodePredicateBuilder.build(cb, q, root, filter, nodeTypes);
        predicates.add(NodePredicateBuilder.fullTextMatch(cb, root, searchForm.getSearch().getQuery()));
        if (filter != null && filter.getIsRoot() != null) {
            predicates.add(cb.equal(root.get("isRoot"), filter.getIsRoot()));
        }
        if (allowed != null) {
            predicates.add(NodePredicateBuilder.dataSetScope(root, allowed));
        }

        q.select(root)
                // No DISTINCT: the metadata criterion is an EXISTS subquery rather than a join,
                // so nothing here multiplies rows any more. It also could not stay — Postgres
                // rejects an ORDER BY expression that is not in the select list of a SELECT
                // DISTINCT, which is every relevance-ordered search.
                .where(predicates.toArray(new Predicate[0]))
                .orderBy(NodePredicateBuilder.searchOrderBy(cb, root, searchForm.getSearch().getQuery()));

        TypedQuery<NodeEntity> query = entityManager.createQuery(q);
        query.setMaxResults(searchForm.getLimit());
        data.setItems(ResourceTransformer.from(query.getResultList()));
        return data;
    }

    @Transactional(readOnly = true)
    public ResourceNetwork fetchRelatedResources(@Valid RelatedResourcesForm form) {
        if(form.getId() == null){
            NodeEntity n = nodeRepository.findByExternalId(form.getExternalId());
            if(n != null){
                form.setId(n.getId());
            } else {
                throw new ObjectNotFoundException(
                        "Resource with id: " + form.getId() +
                        " or externalId: " + form.getExternalId() + " not found.");
            }
        }
        // Gate the traversal on read access to the starting resource's dataset. The reachable
        // network returned by Neo4j is not itself dataset-filtered — see DATASET_ACL_SETUP.md.
        NodeEntity start = nodeRepository.findById(form.getId()).orElseThrow(() ->
                new ObjectNotFoundException("Resource with id: " + form.getId() + " not found."));
        dataSecurity.assertCanRead(start);
        return neo4JService.fetchRelatedNodes(
                form.getId(), form.getDepth(), form.getRelationshipTypes(),
                form.getLimit(), form.getExcludedLabels());
    }

    /**
     * ACL-gated variant of {@link #fetchRelatedResources} that returns the nearest {@code limit} nodes
     * carrying any of {@code endLabels} (breadth-first), via
     * {@link Neo4JService#fetchNearestNodesByEndLabel}.
     */
    public ResourceNetwork fetchNearestRelatedResources(Long id, List<String> endLabels, Integer limit,
                                                        List<String> relationshipTypes, List<String> excludedLabels){
        NodeEntity start = nodeRepository.findById(id).orElseThrow(() ->
                new ObjectNotFoundException("Resource with id: " + id + " not found."));
        dataSecurity.assertCanRead(start);
        return neo4JService.fetchNearestNodesByEndLabel(id, endLabels, limit, relationshipTypes, excludedLabels);
    }
}
