package ai.intellistream.datahub.api.services;

import ai.intellistream.datahub.api.graphtransfer.GraphExportFile;
import ai.intellistream.datahub.api.graphtransfer.GraphExportFile.ExportedNode;
import ai.intellistream.datahub.api.graphtransfer.GraphExportFile.ExportedRelation;
import ai.intellistream.datahub.api.graphtransfer.GraphFileCodec;
import ai.intellistream.datahub.api.graphtransfer.GraphImportResult;
import ai.intellistream.datahub.api.graphtransfer.GraphTransferLimitException;
import ai.intellistream.datahub.api.responses.GraphDataWrapper;
import ai.intellistream.datahub.asset.ResourceNetwork;
import ai.intellistream.datahub.helpers.text.ExternalIds;
import ai.intellistream.datahub.jpa.domains.DatasetEntity;
import ai.intellistream.datahub.jpa.domains.EdgeEntity;
import ai.intellistream.datahub.jpa.domains.NodeEntity;
import ai.intellistream.datahub.jpa.domains.TypeLabels;
import ai.intellistream.datahub.jpa.dto.NameAndExternalIdDTO;
import ai.intellistream.datahub.models.EdgeProxy;
import ai.intellistream.datahub.models.GeoLocation;
import ai.intellistream.datahub.models.RelForm;
import ai.intellistream.datahub.models.RelatedResourcesForm;
import ai.intellistream.datahub.models.Resource;
import ai.intellistream.datahub.models.policy.PolicyWarning;
import ai.intellistream.datahub.repositories.node.EdgeRepository;
import ai.intellistream.datahub.repositories.node.NodeRepository;
import lombok.extern.slf4j.Slf4j;
import org.apache.pulsar.client.api.PulsarClientException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Export / import of a resource graph component as a portable binary file.
 *
 * <p><b>Export</b> walks the whole connected component around a starting resource in Neo4j
 * (via {@link ResourceService#fetchRelatedResources}, which also gates on read access to the
 * starting resource's dataset) and reads everything from the graph: node metadata rides on the
 * nodes as {@code metadata_} properties, and edges carry their dataset id. The one Postgres
 * touch left is resolving dataset ids to externalIds when the dataset node itself is not part
 * of the component (assigned by field, never linked). Geometry is whatever the graph holds —
 * a point, or nothing for non-point geometries (deliberately; see GraphEventNeo4jListener).
 * Everything is keyed by externalId in the file; numeric ids are database identities and do not
 * survive a transfer.
 *
 * <p><b>Import</b> streams: the upload is decoded incrementally ({@link GraphFileCodec#reader})
 * and committed one <em>segment</em> at a time — {@code segmentSize} objects (default 50,000) per
 * transaction — so a 2M-object file becomes ~40 bounded transactions instead of one enormous one,
 * and memory stays flat at one segment regardless of file size. Each segment replays through
 * {@link ResourceService#create}, so naming policy, dataset ACLs, Pulsar publication and the
 * Neo4j mirror all behave exactly as if the caller had created the resources one request at a
 * time; each segment's Pulsar messages go out after that segment's commit.
 *
 * <p>A failure mid-import keeps the segments already committed. That is deliberate, and safe,
 * because import skips what already exists: nodes by externalId, relations by (from, to, type) —
 * so re-uploading the same file fast-forwards through the committed segments and resumes at the
 * failed one. Timeseries cannot be created through the resource api and are reported back rather
 * than failing the import. The format writes all nodes before all relations, and the exporter
 * writes dataset nodes first, so sequential segment processing always finds what a later object
 * references.
 */
@Service
@Slf4j
public class GraphTransferService {

    /** Matches the {@code @Size(max = 1000)} cap on {@code GraphDataWrapper} nodes/relations. */
    private static final int CREATE_BATCH_SIZE = 1000;

    private final ResourceService resourceService;
    private final NodeRepository nodeRepository;
    private final EdgeRepository edgeRepository;
    private final TransactionTemplate transactionTemplate;

    /** Objects (nodes or relations) committed per import transaction. */
    private final int segmentSize;

    public GraphTransferService(ResourceService resourceService, NodeRepository nodeRepository,
                                EdgeRepository edgeRepository,
                                PlatformTransactionManager transactionManager,
                                @Value("${datahub.graph-transfer.segment-size:50000}") int segmentSize) {
        this.resourceService = resourceService;
        this.nodeRepository = nodeRepository;
        this.edgeRepository = edgeRepository;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.segmentSize = segmentSize;
    }

    public record GraphExportPayload(String fileName, byte[] bytes) {
    }

    @Transactional(readOnly = true)
    public GraphExportPayload export(Long id) {
        var form = new RelatedResourcesForm();
        form.setId(id);
        form.setDepth(-1);
        // One node past the cap: enough to detect an oversized component without loading all of it.
        form.setLimit(GraphFileCodec.MAX_NODES + 1);
        ResourceNetwork network = resourceService.fetchRelatedResources(form);
        if (network.nodes().size() > GraphFileCodec.MAX_NODES) {
            throw new GraphTransferLimitException(
                    "The graph component has more than " + GraphFileCodec.MAX_NODES
                            + " nodes and cannot be exported.");
        }
        if (network.edges().size() > GraphFileCodec.MAX_RELATIONS) {
            throw new GraphTransferLimitException(
                    "The graph component has " + network.edges().size() + " relations; the export limit is "
                            + GraphFileCodec.MAX_RELATIONS + ".");
        }

        Map<Long, String> externalIdById = new HashMap<>();
        for (Resource node : network.nodes()) {
            externalIdById.put(node.getId(), node.getExternalId());
        }
        resolveDanglingDataSetIds(network, externalIdById);

        // Dataset nodes first: the import processes the file sequentially in segments, so a
        // dataset must precede the nodes that reference it.
        List<Resource> ordered = new ArrayList<>(network.nodes().size());
        for (Resource node : network.nodes()) {
            if (containsLabel(node.getLabels(), TypeLabels.DATASET)) {
                ordered.add(node);
            }
        }
        for (Resource node : network.nodes()) {
            if (!containsLabel(node.getLabels(), TypeLabels.DATASET)) {
                ordered.add(node);
            }
        }

        List<ExportedNode> nodes = new ArrayList<>(ordered.size());
        for (Resource node : ordered) {
            nodes.add(new ExportedNode(
                    node.getExternalId(),
                    node.getName(),
                    node.getDescription(),
                    node.getSource(),
                    Boolean.TRUE.equals(node.getIsRoot()),
                    node.getGeoLocation() != null ? node.getGeoLocation().getJson() : null,
                    externalIdById.get(node.getDataSetId()),
                    new ArrayList<>(node.getLabels()),
                    new HashMap<>(node.getMetadata())));
        }

        List<ExportedRelation> relations = new ArrayList<>(network.edges().size());
        for (EdgeProxy edge : network.edges()) {
            String from = externalIdById.get(edge.getStart());
            String to = externalIdById.get(edge.getEnd());
            if (from == null || to == null) {
                continue;
            }
            relations.add(new ExportedRelation(
                    from, to, edge.getType(), edge.getDescription(),
                    externalIdById.get(edge.getDataSetId()),
                    edge.getMetadata()));
        }

        var out = new ByteArrayOutputStream();
        try {
            GraphFileCodec.encode(new GraphExportFile(nodes, relations), out);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not serialize the graph export.", e);
        }
        return new GraphExportPayload(exportFileName(network, id), out.toByteArray());
    }

    /**
     * A dataset assigned by field alone (no BELONGS_TO edge) may sit outside the exported
     * component, so its id has no externalId in the network. Resolve those few ids with one
     * Postgres lookup and add them to the map; ids that resolve nowhere stay unmapped and the
     * export simply drops the reference.
     */
    private void resolveDanglingDataSetIds(ResourceNetwork network, Map<Long, String> externalIdById) {
        Set<Long> dangling = new HashSet<>();
        for (Resource node : network.nodes()) {
            if (node.getDataSetId() != null && !externalIdById.containsKey(node.getDataSetId())) {
                dangling.add(node.getDataSetId());
            }
        }
        for (EdgeProxy edge : network.edges()) {
            if (edge.getDataSetId() != null && !externalIdById.containsKey(edge.getDataSetId())) {
                dangling.add(edge.getDataSetId());
            }
        }
        for (List<Long> chunk : chunks(new ArrayList<>(dangling), CREATE_BATCH_SIZE)) {
            for (NameAndExternalIdDTO node : nodeRepository.findAllByIdIn(chunk, NameAndExternalIdDTO.class)) {
                externalIdById.put(node.getId(), node.getExternalId());
            }
        }
    }

    private static String exportFileName(ResourceNetwork network, Long id) {
        String base = network.nodes().stream()
                .filter(n -> id.equals(n.getId()))
                .map(Resource::getExternalId)
                .findFirst()
                .orElse("graph");
        return base.replaceAll("[^A-Za-z0-9._-]", "_") + ".dhgraph";
    }

    public GraphImportResult importGraph(InputStream in) {
        var tally = new ImportTally();
        // Dataset references resolve against datasets created by this import plus datasets that
        // already exist; grows as segments commit.
        Map<Long, Long> dataSetIdByHash = new HashMap<>();

        try (GraphFileCodec.GraphFileReader reader = GraphFileCodec.reader(GraphFileCodec.limited(
                in, GraphFileCodec.MAX_COMPRESSED_BYTES,
                "The file is larger than " + (GraphFileCodec.MAX_COMPRESSED_BYTES / (1024 * 1024)) + " MB."))) {

            List<ExportedNode> nodeSegment = new ArrayList<>();
            ExportedNode node;
            while ((node = reader.nextNode()) != null) {
                nodeSegment.add(node);
                if (nodeSegment.size() >= segmentSize) {
                    commitNodeSegment(nodeSegment, dataSetIdByHash, tally);
                    nodeSegment = new ArrayList<>();
                }
            }
            if (!nodeSegment.isEmpty()) {
                commitNodeSegment(nodeSegment, dataSetIdByHash, tally);
            }

            List<ExportedRelation> relationSegment = new ArrayList<>();
            ExportedRelation relation;
            while ((relation = reader.nextRelation()) != null) {
                relationSegment.add(relation);
                if (relationSegment.size() >= segmentSize) {
                    commitRelationSegment(relationSegment, dataSetIdByHash, tally);
                    relationSegment = new ArrayList<>();
                }
            }
            if (!relationSegment.isEmpty()) {
                commitRelationSegment(relationSegment, dataSetIdByHash, tally);
            }
        }
        return tally.toResult();
    }

    private void commitNodeSegment(List<ExportedNode> segment, Map<Long, Long> dataSetIdByHash,
                                   ImportTally tally) {
        transactionTemplate.executeWithoutResult(status -> importNodeSegment(segment, dataSetIdByHash, tally));
        tally.segments++;
        log.info("Graph import segment {} committed: {} nodes so far ({} skipped)",
                tally.segments, tally.nodesCreated, tally.nodesSkippedExisting);
    }

    private void commitRelationSegment(List<ExportedRelation> segment, Map<Long, Long> dataSetIdByHash,
                                       ImportTally tally) {
        transactionTemplate.executeWithoutResult(status -> importRelationSegment(segment, dataSetIdByHash, tally));
        tally.segments++;
        log.info("Graph import segment {} committed: {} relations so far ({} skipped)",
                tally.segments, tally.relationsCreated, tally.relationsSkipped);
    }

    /** One node segment, inside its own transaction. */
    private void importNodeSegment(List<ExportedNode> segment, Map<Long, Long> dataSetIdByHash,
                                   ImportTally tally) {
        // Nodes that already exist in this tenant are skipped; existing datasets among them
        // feed the dataset-reference map.
        Set<Long> existingHashes = new HashSet<>();
        List<String> externalIds = segment.stream().map(ExportedNode::externalId).toList();
        for (List<String> chunk : chunks(externalIds, CREATE_BATCH_SIZE)) {
            for (NodeEntity entity : nodeRepository.findAllByExternalIdIn(chunk)) {
                existingHashes.add(entity.getExternalIdHash());
                if (entity instanceof DatasetEntity) {
                    dataSetIdByHash.put(entity.getExternalIdHash(), entity.getId());
                }
            }
        }

        List<ExportedNode> dataSets = new ArrayList<>();
        List<ExportedNode> others = new ArrayList<>();
        for (ExportedNode node : segment) {
            if (existingHashes.contains(ExternalIds.hash(node.externalId()))) {
                tally.nodesSkippedExisting++;
            } else if (containsLabel(node.labels(), TypeLabels.TIMESERIES)) {
                tally.nodesSkippedTimeseries.add(node.externalId());
            } else if (containsLabel(node.labels(), TypeLabels.DATASET)) {
                dataSets.add(node);
            } else {
                others.add(node);
            }
        }

        // Datasets first (within the segment; the exporter also orders them first in the file),
        // so the nodes that follow can resolve their dataset reference.
        for (List<ExportedNode> chunk : chunks(dataSets, CREATE_BATCH_SIZE)) {
            GraphDataWrapper<Resource, EdgeProxy> created = createNodes(chunk, dataSetIdByHash, tally);
            for (Resource resource : created.getNodes()) {
                dataSetIdByHash.put(ExternalIds.hash(resource.getExternalId()), resource.getId());
            }
        }

        // A node may reference a dataset that exists in the tenant without being in the file;
        // resolve those hashes once per segment before mapping.
        resolvePreexistingDataSets(others, dataSetIdByHash);
        for (List<ExportedNode> chunk : chunks(others, CREATE_BATCH_SIZE)) {
            createNodes(chunk, dataSetIdByHash, tally);
        }
    }

    private GraphDataWrapper<Resource, EdgeProxy> createNodes(List<ExportedNode> chunk,
                                                              Map<Long, Long> dataSetIdByHash,
                                                              ImportTally tally) {
        var wrapper = new GraphDataWrapper<Resource, RelForm>();
        chunk.forEach(n -> wrapper.getNodes().add(toResource(n, dataSetIdByHash, tally)));
        GraphDataWrapper<Resource, EdgeProxy> created = create(wrapper);
        tally.nodesCreated += created.getNodes().size();
        collectWarnings(created, tally.warnings);
        return created;
    }

    private void resolvePreexistingDataSets(List<ExportedNode> nodes, Map<Long, Long> dataSetIdByHash) {
        List<Long> unresolved = nodes.stream()
                .map(ExportedNode::dataSetExternalId)
                .filter(java.util.Objects::nonNull)
                .map(ExternalIds::hash)
                .distinct()
                .filter(hash -> !dataSetIdByHash.containsKey(hash))
                .toList();
        for (List<Long> chunk : chunks(unresolved, CREATE_BATCH_SIZE)) {
            for (NodeEntity entity : nodeRepository.findAllByExternalIdHashIn(chunk)) {
                if (entity instanceof DatasetEntity) {
                    dataSetIdByHash.put(entity.getExternalIdHash(), entity.getId());
                }
            }
        }
    }

    /** One relation segment, inside its own transaction. Every node in the file is committed by now. */
    private void importRelationSegment(List<ExportedRelation> segment, Map<Long, Long> dataSetIdByHash,
                                       ImportTally tally) {
        // Resolve every endpoint from the database: nodes created by earlier segments, or
        // pre-existing ones. An endpoint that resolves nowhere (e.g. a timeseries that was
        // skipped because it does not exist here) skips the relation.
        Set<Long> endpointHashes = new HashSet<>();
        for (ExportedRelation relation : segment) {
            endpointHashes.add(ExternalIds.hash(relation.fromExternalId()));
            endpointHashes.add(ExternalIds.hash(relation.toExternalId()));
        }
        Map<Long, Long> idByHash = new HashMap<>();
        for (List<Long> chunk : chunks(new ArrayList<>(endpointHashes), CREATE_BATCH_SIZE)) {
            for (NameAndExternalIdDTO dto : nodeRepository.findAllByExternalIdHashIn(chunk, NameAndExternalIdDTO.class)) {
                idByHash.put(dto.getExternalIdHash(), dto.getId());
            }
        }

        // Relations already present between these endpoints are skipped — this is also what makes
        // re-running an interrupted import resume cleanly through relation segments.
        Set<String> existingKeys = existingEdgeKeys(new HashSet<>(idByHash.values()));

        List<RelForm> forms = new ArrayList<>();
        for (ExportedRelation relation : segment) {
            Long fromId = idByHash.get(ExternalIds.hash(relation.fromExternalId()));
            Long toId = idByHash.get(ExternalIds.hash(relation.toExternalId()));
            if (fromId == null || toId == null) {
                tally.relationsSkipped++;
                continue;
            }
            if (existingKeys.contains(edgeKey(fromId, toId, relation.type()))) {
                tally.relationsSkipped++;
                continue;
            }
            forms.add(toRelForm(relation, dataSetIdByHash, tally));
        }

        for (List<RelForm> chunk : chunks(forms, CREATE_BATCH_SIZE)) {
            var wrapper = new GraphDataWrapper<Resource, RelForm>();
            wrapper.getRelations().addAll(chunk);
            GraphDataWrapper<Resource, EdgeProxy> created = create(wrapper);
            tally.relationsCreated += created.getRelations().size();
            collectWarnings(created, tally.warnings);
        }
    }

    /** {@link ResourceService#create} with its checked Pulsar exception adapted for lambda use. */
    private GraphDataWrapper<Resource, EdgeProxy> create(GraphDataWrapper<Resource, RelForm> wrapper) {
        try {
            return resourceService.create(wrapper);
        } catch (PulsarClientException e) {
            throw new IllegalStateException("Could not publish the import to Pulsar.", e);
        }
    }

    /** The (start, end, type) keys of edges already present between {@code endpointIds}. */
    private Set<String> existingEdgeKeys(Set<Long> endpointIds) {
        Set<String> keys = new HashSet<>();
        for (List<Long> chunk : chunks(new ArrayList<>(endpointIds), CREATE_BATCH_SIZE)) {
            for (EdgeEntity edge : edgeRepository.findAllByStartIn(chunk, EdgeEntity.class)) {
                if (endpointIds.contains(edge.getEnd())) {
                    keys.add(edgeKey(edge.getStart(), edge.getEnd(), edge.getRelationshipType().getName()));
                }
            }
        }
        return keys;
    }

    /** Running totals across segments; one instance per import. */
    private static final class ImportTally {
        int nodesCreated;
        int relationsCreated;
        int nodesSkippedExisting;
        final List<String> nodesSkippedTimeseries = new ArrayList<>();
        int relationsSkipped;
        int dataSetReferencesDropped;
        int segments;
        final List<PolicyWarning> warnings = new ArrayList<>();

        GraphImportResult toResult() {
            return new GraphImportResult(nodesCreated, relationsCreated, nodesSkippedExisting,
                    nodesSkippedTimeseries, relationsSkipped, dataSetReferencesDropped, segments, warnings);
        }
    }

    private static String edgeKey(Long start, Long end, String type) {
        return start + "|" + end + "|" + (type == null ? "" : type.toUpperCase());
    }

    private static Resource toResource(ExportedNode node, Map<Long, Long> dataSetIdByHash,
                                       ImportTally tally) {
        Resource resource = new Resource();
        resource.setExternalId(node.externalId());
        resource.setName(node.name());
        resource.setDescription(node.description());
        resource.setSource(node.source());
        resource.setIsRoot(node.isRoot());
        resource.setLabels(node.labels());
        resource.setMetadata(new HashMap<>(node.metadata()));
        if (node.geoJson() != null) {
            resource.setGeoLocation(new GeoLocation(node.geoJson()));
        }
        if (node.dataSetExternalId() != null) {
            Long dataSetId = dataSetIdByHash.get(ExternalIds.hash(node.dataSetExternalId()));
            if (dataSetId != null) {
                resource.setDataSetId(dataSetId);
            } else {
                tally.dataSetReferencesDropped++;
            }
        }
        return resource;
    }

    private static RelForm toRelForm(ExportedRelation relation, Map<Long, Long> dataSetIdByHash,
                                     ImportTally tally) {
        RelForm form = new RelForm();
        form.setFromExternalId(relation.fromExternalId());
        form.setToExternalId(relation.toExternalId());
        form.setName(relation.type());
        form.setDescription(relation.description());
        form.setMetadata(new HashMap<>(relation.metadata()));
        if (relation.dataSetExternalId() != null) {
            Long dataSetId = dataSetIdByHash.get(ExternalIds.hash(relation.dataSetExternalId()));
            if (dataSetId != null) {
                form.setDataSetId(dataSetId);
            } else {
                tally.dataSetReferencesDropped++;
            }
        }
        return form;
    }

    private static boolean containsLabel(List<String> labels, String label) {
        return labels.stream().anyMatch(l -> l != null && l.equalsIgnoreCase(label));
    }

    private static void collectWarnings(GraphDataWrapper<Resource, EdgeProxy> created,
                                        Collection<PolicyWarning> warnings) {
        if (created.getWarnings() != null) {
            warnings.addAll(created.getWarnings());
        }
    }

    private static <T> List<List<T>> chunks(List<T> all, int size) {
        List<List<T>> chunks = new ArrayList<>();
        for (int i = 0; i < all.size(); i += size) {
            chunks.add(all.subList(i, Math.min(i + size, all.size())));
        }
        return chunks;
    }
}
