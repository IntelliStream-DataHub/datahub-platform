package ai.intellistream.datahub.api.services;

import ai.intellistream.datahub.api.graphtransfer.GraphExportFile;
import ai.intellistream.datahub.api.graphtransfer.GraphExportFile.ExportedNode;
import ai.intellistream.datahub.api.graphtransfer.GraphExportFile.ExportedRelation;
import ai.intellistream.datahub.api.graphtransfer.GraphFileCodec;
import ai.intellistream.datahub.api.graphtransfer.GraphImportResult;
import ai.intellistream.datahub.api.responses.GraphDataWrapper;
import ai.intellistream.datahub.asset.ResourceNetwork;
import ai.intellistream.datahub.helpers.text.ExternalIds;
import ai.intellistream.datahub.jpa.domains.AssetEntity;
import ai.intellistream.datahub.jpa.domains.DatasetEntity;
import ai.intellistream.datahub.jpa.domains.EdgeEntity;
import ai.intellistream.datahub.jpa.domains.NodeEntity;
import ai.intellistream.datahub.jpa.domains.TypeLabels;
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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
 * starting resource's dataset), then enriches nodes and edges from Postgres — the system of
 * record — because the Neo4j mirror carries no node metadata and only a lossy point geometry.
 * Everything is keyed by externalId in the file; numeric ids are database identities and do not
 * survive a transfer.
 *
 * <p><b>Import</b> replays the file through {@link ResourceService#create}, so naming policy,
 * dataset ACLs, Pulsar publication and the Neo4j mirror all behave exactly as if the caller had
 * created the resources one request at a time. Nodes that already exist (by externalId) are
 * skipped, which makes re-importing a file idempotent. Timeseries cannot be created through the
 * resource api and are reported back instead of failing the import. The whole import is one
 * transaction: a validation failure in any batch rolls back everything, and the Pulsar messages
 * only go out after the final commit.
 */
@Service
@Slf4j
public class GraphTransferService {

    /** Matches the {@code @Size(max = 1000)} cap on {@code GraphDataWrapper} nodes/relations. */
    private static final int CREATE_BATCH_SIZE = 1000;

    private final ResourceService resourceService;
    private final NodeRepository nodeRepository;
    private final EdgeRepository edgeRepository;

    public GraphTransferService(ResourceService resourceService, NodeRepository nodeRepository,
                                EdgeRepository edgeRepository) {
        this.resourceService = resourceService;
        this.nodeRepository = nodeRepository;
        this.edgeRepository = edgeRepository;
    }

    public record GraphExportPayload(String fileName, byte[] bytes) {
    }

    @Transactional(readOnly = true)
    public GraphExportPayload export(Long id) {
        var form = new RelatedResourcesForm();
        form.setId(id);
        form.setDepth(-1);
        form.setLimit(-1);
        ResourceNetwork network = resourceService.fetchRelatedResources(form);

        Map<Long, String> externalIdById = new HashMap<>();
        List<String> externalIds = new ArrayList<>();
        for (Resource node : network.nodes()) {
            externalIdById.put(node.getId(), node.getExternalId());
            externalIds.add(node.getExternalId());
        }

        // Postgres enrichment: node metadata and full (non-point) geometry only live there.
        Map<Long, NodeEntity> entitiesByHash = new HashMap<>();
        for (List<String> chunk : chunks(externalIds, CREATE_BATCH_SIZE)) {
            for (NodeEntity entity : nodeRepository.findAllByExternalIdIn(chunk)) {
                entitiesByHash.put(entity.getExternalIdHash(), entity);
            }
        }

        List<ExportedNode> nodes = new ArrayList<>(network.nodes().size());
        for (Resource node : network.nodes()) {
            NodeEntity entity = entitiesByHash.get(ExternalIds.hash(node.getExternalId()));
            nodes.add(toExportedNode(node, entity));
        }

        Map<Long, EdgeEntity> edgesById = new HashMap<>();
        List<Long> edgeIds = network.edges().stream().map(EdgeProxy::getId).toList();
        for (List<Long> chunk : chunks(edgeIds, CREATE_BATCH_SIZE)) {
            for (EdgeEntity entity : edgeRepository.findAllByIdIn(chunk, EdgeEntity.class)) {
                edgesById.put(entity.getId(), entity);
            }
        }

        List<ExportedRelation> relations = new ArrayList<>(network.edges().size());
        for (EdgeProxy edge : network.edges()) {
            String from = externalIdById.get(edge.getStart());
            String to = externalIdById.get(edge.getEnd());
            if (from == null || to == null) {
                continue;
            }
            EdgeEntity entity = edgesById.get(edge.getId());
            relations.add(new ExportedRelation(
                    from, to, edge.getType(),
                    entity != null ? entity.getDescription() : edge.getDescription(),
                    entity != null && entity.getMetadata() != null
                            ? new HashMap<>(entity.getMetadata())
                            : edge.getMetadata()));
        }

        var out = new ByteArrayOutputStream();
        try {
            GraphFileCodec.encode(new GraphExportFile(nodes, relations), out);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not serialize the graph export.", e);
        }
        return new GraphExportPayload(exportFileName(network, id), out.toByteArray());
    }

    private static ExportedNode toExportedNode(Resource node, NodeEntity entity) {
        String geoJson = null;
        if (entity instanceof AssetEntity asset && asset.getGeoLocation() != null) {
            geoJson = asset.getGeoLocation();
        } else if (node.getGeoLocation() != null) {
            geoJson = node.getGeoLocation().getJson();
        }
        String dataSetExternalId = null;
        if (entity != null && entity.getDataSet() != null) {
            dataSetExternalId = entity.getDataSet().getExternalId();
        }
        return new ExportedNode(
                entity != null ? entity.getExternalId() : node.getExternalId(),
                entity != null ? entity.getName() : node.getName(),
                entity != null ? entity.getDescription() : node.getDescription(),
                entity != null ? entity.getSource() : node.getSource(),
                entity != null ? Boolean.TRUE.equals(entity.getIsRoot()) : Boolean.TRUE.equals(node.getIsRoot()),
                geoJson,
                dataSetExternalId,
                new ArrayList<>(node.getLabels()),
                entity != null && entity.getMetadata() != null
                        ? new HashMap<>(entity.getMetadata())
                        : new HashMap<>());
    }

    private static String exportFileName(ResourceNetwork network, Long id) {
        String base = network.nodes().stream()
                .filter(n -> id.equals(n.getId()))
                .map(Resource::getExternalId)
                .findFirst()
                .orElse("graph");
        return base.replaceAll("[^A-Za-z0-9._-]", "_") + ".dhgraph";
    }

    @Transactional(rollbackFor = Exception.class)
    public GraphImportResult importGraph(InputStream in) throws PulsarClientException {
        GraphExportFile file = GraphFileCodec.decode(in);

        Map<Long, NodeEntity> existingByHash = new HashMap<>();
        List<String> fileExternalIds = file.nodes().stream().map(ExportedNode::externalId).toList();
        for (List<String> chunk : chunks(fileExternalIds, CREATE_BATCH_SIZE)) {
            for (NodeEntity entity : nodeRepository.findAllByExternalIdIn(chunk)) {
                existingByHash.put(entity.getExternalIdHash(), entity);
            }
        }

        // Partition the file's nodes. Datasets are created first so other nodes can resolve their
        // dataset reference; timeseries can only be created through the timeseries api, so missing
        // ones are reported back rather than failing the whole import.
        List<ExportedNode> dataSets = new ArrayList<>();
        List<ExportedNode> others = new ArrayList<>();
        List<String> skippedTimeseries = new ArrayList<>();
        int skippedExisting = 0;
        for (ExportedNode node : file.nodes()) {
            if (existingByHash.containsKey(ExternalIds.hash(node.externalId()))) {
                skippedExisting++;
            } else if (containsLabel(node.labels(), TypeLabels.TIMESERIES)) {
                skippedTimeseries.add(node.externalId());
            } else if (containsLabel(node.labels(), TypeLabels.DATASET)) {
                dataSets.add(node);
            } else {
                others.add(node);
            }
        }

        int nodesCreated = 0;
        List<PolicyWarning> warnings = new ArrayList<>();

        // Dataset references are resolved against datasets created here plus datasets that
        // already exist; anything else in the dataSetExternalId slot is dropped, counted.
        Map<Long, Long> dataSetIdByHash = new HashMap<>();
        existingByHash.forEach((hash, entity) -> {
            if (entity instanceof DatasetEntity) {
                dataSetIdByHash.put(hash, entity.getId());
            }
        });
        int[] droppedDataSetRefs = { 0 };

        for (List<ExportedNode> chunk : chunks(dataSets, CREATE_BATCH_SIZE)) {
            var wrapper = new GraphDataWrapper<Resource, RelForm>();
            chunk.forEach(n -> wrapper.getNodes().add(toResource(n, dataSetIdByHash, droppedDataSetRefs)));
            GraphDataWrapper<Resource, EdgeProxy> created = resourceService.create(wrapper);
            for (Resource resource : created.getNodes()) {
                dataSetIdByHash.put(ExternalIds.hash(resource.getExternalId()), resource.getId());
            }
            nodesCreated += created.getNodes().size();
            collectWarnings(created, warnings);
        }

        // Relations whose endpoints are neither in the tenant nor being created now (e.g. a
        // skipped timeseries that does not exist here) cannot be replayed. Relations between two
        // pre-existing nodes are deduplicated against the edges already present.
        Set<Long> availableHashes = new HashSet<>(existingByHash.keySet());
        Set<String> unavailable = new HashSet<>(skippedTimeseries);
        file.nodes().stream()
                .filter(n -> !unavailable.contains(n.externalId()))
                .forEach(n -> availableHashes.add(ExternalIds.hash(n.externalId())));

        Set<String> existingEdgeKeys = existingEdgeKeys(file, existingByHash);
        List<RelForm> relations = new ArrayList<>();
        int skippedRelations = 0;
        for (ExportedRelation relation : file.relations()) {
            long fromHash = ExternalIds.hash(relation.fromExternalId());
            long toHash = ExternalIds.hash(relation.toExternalId());
            if (!availableHashes.contains(fromHash) || !availableHashes.contains(toHash)) {
                skippedRelations++;
                continue;
            }
            NodeEntity fromExisting = existingByHash.get(fromHash);
            NodeEntity toExisting = existingByHash.get(toHash);
            if (fromExisting != null && toExisting != null && existingEdgeKeys.contains(
                    edgeKey(fromExisting.getId(), toExisting.getId(), relation.type()))) {
                skippedRelations++;
                continue;
            }
            relations.add(toRelForm(relation));
        }

        for (List<ExportedNode> chunk : chunks(others, CREATE_BATCH_SIZE)) {
            var wrapper = new GraphDataWrapper<Resource, RelForm>();
            chunk.forEach(n -> wrapper.getNodes().add(toResource(n, dataSetIdByHash, droppedDataSetRefs)));
            GraphDataWrapper<Resource, EdgeProxy> created = resourceService.create(wrapper);
            nodesCreated += created.getNodes().size();
            collectWarnings(created, warnings);
        }

        int relationsCreated = 0;
        for (List<RelForm> chunk : chunks(relations, CREATE_BATCH_SIZE)) {
            var wrapper = new GraphDataWrapper<Resource, RelForm>();
            wrapper.getRelations().addAll(chunk);
            GraphDataWrapper<Resource, EdgeProxy> created = resourceService.create(wrapper);
            relationsCreated += created.getRelations().size();
            collectWarnings(created, warnings);
        }

        return new GraphImportResult(nodesCreated, relationsCreated, skippedExisting,
                skippedTimeseries, skippedRelations, droppedDataSetRefs[0], warnings);
    }

    /**
     * The (start, end, type) keys of edges already present between the file's pre-existing
     * endpoint nodes — the only relations a re-import could duplicate.
     */
    private Set<String> existingEdgeKeys(GraphExportFile file, Map<Long, NodeEntity> existingByHash) {
        Set<Long> endpointIds = new HashSet<>();
        for (ExportedRelation relation : file.relations()) {
            NodeEntity from = existingByHash.get(ExternalIds.hash(relation.fromExternalId()));
            NodeEntity to = existingByHash.get(ExternalIds.hash(relation.toExternalId()));
            if (from != null && to != null) {
                endpointIds.add(from.getId());
                endpointIds.add(to.getId());
            }
        }
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

    private static String edgeKey(Long start, Long end, String type) {
        return start + "|" + end + "|" + (type == null ? "" : type.toUpperCase());
    }

    private static Resource toResource(ExportedNode node, Map<Long, Long> dataSetIdByHash,
                                       int[] droppedDataSetRefs) {
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
                droppedDataSetRefs[0]++;
            }
        }
        return resource;
    }

    private static RelForm toRelForm(ExportedRelation relation) {
        RelForm form = new RelForm();
        form.setFromExternalId(relation.fromExternalId());
        form.setToExternalId(relation.toExternalId());
        form.setName(relation.type());
        form.setDescription(relation.description());
        form.setMetadata(new HashMap<>(relation.metadata()));
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
