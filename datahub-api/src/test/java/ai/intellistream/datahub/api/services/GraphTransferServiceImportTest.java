package ai.intellistream.datahub.api.services;

import ai.intellistream.datahub.api.graphtransfer.GraphExportFile;
import ai.intellistream.datahub.api.graphtransfer.GraphExportFile.ExportedNode;
import ai.intellistream.datahub.api.graphtransfer.GraphExportFile.ExportedRelation;
import ai.intellistream.datahub.api.graphtransfer.GraphFileCodec;
import ai.intellistream.datahub.api.graphtransfer.GraphImportResult;
import ai.intellistream.datahub.api.responses.GraphDataWrapper;
import ai.intellistream.datahub.helpers.text.ExternalIds;
import ai.intellistream.datahub.jpa.dto.NameAndExternalIdDTO;
import ai.intellistream.datahub.models.EdgeProxy;
import ai.intellistream.datahub.models.RelForm;
import ai.intellistream.datahub.models.Resource;
import ai.intellistream.datahub.repositories.node.EdgeRepository;
import ai.intellistream.datahub.repositories.node.NodeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.invocation.InvocationOnMock;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The streaming segmented import against mocked collaborators: a file bigger than the segment
 * size must commit in multiple bounded transactions, resolve dataset references across segments,
 * and report the totals. Uses a tiny segment size so the file stays small.
 */
class GraphTransferServiceImportTest {

    private static final int SEGMENT_SIZE = 10;

    private ResourceService resourceService;
    private NodeRepository nodeRepository;
    private EdgeRepository edgeRepository;
    private final AtomicInteger commits = new AtomicInteger();
    private final List<GraphDataWrapper<ai.intellistream.datahub.models.NodeModel, RelForm>> createCalls = new ArrayList<>();
    private GraphTransferService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        resourceService = mock(ResourceService.class);
        nodeRepository = mock(NodeRepository.class);
        edgeRepository = mock(EdgeRepository.class);
        commits.set(0);
        createCalls.clear();

        // Nothing exists yet in the tenant.
        when(nodeRepository.findAllByExternalIdIn(anyList())).thenReturn(List.of());
        when(edgeRepository.findAllByStartIn(anyList(), eq(ai.intellistream.datahub.jpa.domains.EdgeEntity.class)))
                .thenReturn(List.of());

        // Relation endpoints resolve to the ids "created" by the stubbed create() below.
        when(nodeRepository.findAllByExternalIdHashIn(anyList(), eq(NameAndExternalIdDTO.class)))
                .thenAnswer(inv -> ((List<Long>) inv.getArgument(0)).stream()
                        .map(hash -> new NameAndExternalIdDTO(hash, null, null, hash))
                        .toList());

        // create() echoes the wrapper back with server-assigned ids (the externalId hash, so the
        // endpoint resolution above agrees with it).
        AtomicLong edgeIds = new AtomicLong(1);
        when(resourceService.create(any())).thenAnswer((InvocationOnMock inv) -> {
            GraphDataWrapper<ai.intellistream.datahub.models.NodeModel, RelForm> in = inv.getArgument(0);
            createCalls.add(in);
            var out = new GraphDataWrapper<ai.intellistream.datahub.models.NodeModel, EdgeProxy>();
            in.getNodes().forEach(n -> {
                n.setId(ExternalIds.hash(n.getExternalId()));
                out.getNodes().add(n);
            });
            in.getRelations().forEach(r -> out.getRelations().add(new EdgeProxy(edgeIds.getAndIncrement())));
            return out;
        });

        PlatformTransactionManager txManager = new PlatformTransactionManager() {
            @Override
            public TransactionStatus getTransaction(TransactionDefinition definition) {
                return new SimpleTransactionStatus();
            }

            @Override
            public void commit(TransactionStatus status) {
                commits.incrementAndGet();
            }

            @Override
            public void rollback(TransactionStatus status) {
            }
        };

        service = new GraphTransferService(resourceService, nodeRepository, edgeRepository,
                txManager, SEGMENT_SIZE);
    }

    private static byte[] encode(List<ExportedNode> nodes, List<ExportedRelation> relations) throws IOException {
        var out = new ByteArrayOutputStream();
        GraphFileCodec.encode(new GraphExportFile(nodes, relations), out);
        return out.toByteArray();
    }

    private static ExportedNode node(String externalId, String dataSetExternalId, String... labels) {
        return new ExportedNode(externalId, "Name " + externalId, null, null, false, null,
                dataSetExternalId, List.of(labels), Map.of());
    }

    @Test
    void importsInSegmentsOfTheConfiguredSize() throws Exception {
        // 25 nodes (3 segments of <=10) + 12 relations (2 segments) = 5 transactions.
        List<ExportedNode> nodes = new ArrayList<>();
        nodes.add(node("ds_main", null, "DATASET"));
        for (int i = 1; i < 25; i++) {
            nodes.add(node("asset_" + i, "ds_main", "ASSET"));
        }
        List<ExportedRelation> relations = new ArrayList<>();
        for (int i = 1; i <= 12; i++) {
            relations.add(new ExportedRelation("asset_" + i, "asset_" + (i + 1), "FLOWS_TO", null, null, Map.of()));
        }

        GraphImportResult result = service.importGraph(new ByteArrayInputStream(encode(nodes, relations)));

        assertThat(commits.get()).isEqualTo(5);
        assertThat(result.segments()).isEqualTo(5);
        assertThat(result.nodesCreated()).isEqualTo(25);
        assertThat(result.relationsCreated()).isEqualTo(12);
        assertThat(result.relationsSkipped()).isZero();
        assertThat(result.dataSetReferencesDropped()).isZero();
    }

    @Test
    void resolvesTheDatasetCreatedInAnEarlierSegmentForLaterSegments() throws Exception {
        List<ExportedNode> nodes = new ArrayList<>();
        nodes.add(node("ds_main", null, "DATASET"));
        for (int i = 1; i < 25; i++) {
            nodes.add(node("asset_" + i, "ds_main", "ASSET"));
        }

        service.importGraph(new ByteArrayInputStream(encode(nodes, List.of())));

        long dataSetId = ExternalIds.hash("ds_main");
        List<ai.intellistream.datahub.models.NodeModel> created = createCalls.stream()
                .flatMap(w -> w.getNodes().stream())
                .filter(n -> n.getExternalId().startsWith("asset_"))
                .toList();
        assertThat(created).hasSize(24);
        // Every asset — including those in segments after the dataset's — carries the dataset id.
        assertThat(created).allSatisfy(n -> assertThat(n.getDataSetId()).isEqualTo(dataSetId));
    }

    @Test
    void streamedExportRoundTripsThroughTheSegmentedImport() throws Exception {
        // A component as the graph returns it: one dataset, 24 assets in it, a 23-edge chain.
        // Typed the way a graph read hands them over, so the export exercises the per-type
        // accessors for isRoot and geoLocation rather than a flat shape that has neither.
        var networkNodes = new java.util.HashSet<ai.intellistream.datahub.models.NodeModel>();
        var networkEdges = new java.util.HashSet<EdgeProxy>();
        var dataSet = new ai.intellistream.datahub.models.DataSetModel();
        dataSet.setId(1L);
        dataSet.setExternalId("ds_main");
        dataSet.setName("Main dataset");
        dataSet.setLabels(List.of("DATASET"));
        networkNodes.add(dataSet);
        for (long i = 2; i <= 25; i++) {
            var asset = new ai.intellistream.datahub.models.Asset();
            asset.setId(i);
            asset.setExternalId("asset_" + i);
            asset.setName("Asset " + i);
            asset.setLabels(List.of("ASSET"));
            asset.setDataSetId(1L);
            networkNodes.add(asset);
            if (i > 2) {
                networkEdges.add(new EdgeProxy(i, i - 1, i, "FLOWS_TO", 9L, new java.util.HashMap<>()));
            }
        }
        when(resourceService.fetchRelatedResources(any())).thenReturn(
                new ai.intellistream.datahub.asset.ResourceNetwork(networkNodes, networkEdges, new java.util.HashSet<>()));

        var prepared = service.prepareExport(1L);
        var file = new ByteArrayOutputStream();
        service.writeExport(prepared, file);

        GraphImportResult result = service.importGraph(new ByteArrayInputStream(file.toByteArray()));

        assertThat(prepared.fileName()).isEqualTo("ds_main.dhgraph");
        assertThat(result.nodesCreated()).isEqualTo(25);
        assertThat(result.relationsCreated()).isEqualTo(23);
        assertThat(result.relationsSkipped()).isZero();
        assertThat(result.dataSetReferencesDropped()).isZero();
        // The dataset leads the file, so every asset resolved its dataset reference on import.
        List<ai.intellistream.datahub.models.NodeModel> createdAssets = createCalls.stream()
                .flatMap(w -> w.getNodes().stream())
                .filter(n -> n.getExternalId().startsWith("asset_"))
                .toList();
        assertThat(createdAssets).hasSize(24);
        assertThat(createdAssets).allSatisfy(n ->
                assertThat(n.getDataSetId()).isEqualTo(ExternalIds.hash("ds_main")));
    }

    @Test
    void skipsRelationsWhoseEndpointDoesNotResolve() throws Exception {
        var nodes = List.of(node("asset_1", null, "ASSET"));
        var relations = List.of(
                new ExportedRelation("asset_1", "ts_missing", "PUBLISHES_DATA_TO", null, null, Map.of()));
        // ts_missing was never created: endpoint resolution only answers for asset_1.
        when(nodeRepository.findAllByExternalIdHashIn(anyList(), eq(NameAndExternalIdDTO.class)))
                .thenAnswer(inv -> {
                    long known = ExternalIds.hash("asset_1");
                    return ((List<Long>) inv.getArgument(0)).stream()
                            .filter(h -> h == known)
                            .map(h -> new NameAndExternalIdDTO(h, null, null, h))
                            .toList();
                });

        GraphImportResult result = service.importGraph(new ByteArrayInputStream(encode(nodes, relations)));

        assertThat(result.relationsCreated()).isZero();
        assertThat(result.relationsSkipped()).isEqualTo(1);
    }
}
