// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.services;

import ai.intellistream.datahub.errors.InvalidResourceException;
import ai.intellistream.datahub.jpa.domains.AssetEntity;
import ai.intellistream.datahub.jpa.domains.DatasetEntity;
import ai.intellistream.datahub.jpa.domains.FunctionEntity;
import ai.intellistream.datahub.jpa.domains.Label;
import ai.intellistream.datahub.jpa.domains.NodeEntity;
import ai.intellistream.datahub.jpa.domains.PolicyEntity;
import ai.intellistream.datahub.timeseries.Timeseries;
import ai.intellistream.datahub.jpa.domains.TimeseriesEntity;
import ai.intellistream.datahub.jpa.domains.ResourceEntity;
import ai.intellistream.datahub.models.Resource;
import ai.intellistream.datahub.repositories.node.DataSetRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link NodeService#createFromResource} — the create-side of type-label handling:
 * a node is typed from its label names, may carry at most one type-label, and the {@code TIMESERIES}
 * label (created through its own API) is rejected.
 */
class NodeServiceTest {

    private LabelService labelService;
    private NodeService nodeService;

    @BeforeEach
    void setUp() {
        labelService = mock(LabelService.class);
        nodeService = new NodeService(labelService, mock(DataSetRepository.class));
        // Echo the requested names back as Label entities (Label.setName upper-cases them).
        when(labelService.findAllAndCreateFromNames(anyList())).thenAnswer(inv -> {
            List<String> names = inv.getArgument(0);
            return names.stream().map(n -> {
                Label l = new Label();
                l.setName(n);
                return l;
            }).toList();
        });
    }

    private Resource resource(String... labels) {
        Resource r = new Resource();
        r.setLabels(List.of(labels));
        r.setName("Some Node");
        r.setExternalId("some_node");
        r.setDescription("desc");
        r.setIsRoot(false);
        r.setMetadata(new HashMap<>());
        return r;
    }

    @Test
    void rejectsMoreThanOneTypeLabel() {
        assertThrows(InvalidResourceException.class,
                () -> nodeService.createFromResource(resource("ASSET", "DATASET")));
    }

    @Test
    void assetLabelCreatesAssetEntity() {
        NodeEntity node = nodeService.createFromResource(resource("ASSET", "PIPE"));
        assertInstanceOf(AssetEntity.class, node);
    }

    @Test
    void datasetLabelCreatesDatasetEntity() {
        NodeEntity node = nodeService.createFromResource(resource("DATASET", "CHEMICALS"));
        assertInstanceOf(DatasetEntity.class, node);
    }

    @Test
    void policyLabelCreatesPolicyEntity() {
        NodeEntity node = nodeService.createFromResource(resource("POLICY"));
        assertInstanceOf(PolicyEntity.class, node);
    }

    @Test
    void noTypeLabelCreatesPlainResourceEntity() {
        NodeEntity node = nodeService.createFromResource(resource("PIPE", "SENSOR"));
        assertInstanceOf(ResourceEntity.class, node);
        // The resource keeps its (non-type) labels.
        assertEquals(List.of("PIPE", "SENSOR").size(), node.getLabels().split(",").length);
    }

    /**
     * TIMESERIES is creatable now, but only through the Timeseries shape — a flat resource body
     * cannot carry the type-specific fields (unit, value type), so a bare TIMESERIES label on a
     * Resource-shaped body is still rejected. Over HTTP this is unreachable: the label-keyed
     * deserializer binds such a body as a Timeseries before the service sees it.
     */
    @Test
    void timeseriesLabelOnAFlatResourceShapeIsRejected() {
        assertThrows(InvalidResourceException.class,
                () -> nodeService.createFromResource(resource("TIMESERIES")));
    }

    @Test
    void aTimeseriesBodyCreatesATimeseriesEntity() {
        Timeseries ts = new Timeseries();
        ts.setExternalId("engine_temp");
        ts.setName("Engine Temp");
        ts.setUnit("Deg C");

        NodeEntity node = nodeService.createFromResource(ts);

        TimeseriesEntity entity = assertInstanceOf(TimeseriesEntity.class, node);
        assertEquals("Deg C", entity.getUnit());
        assertTrue(entity.getLabels().contains("TIMESERIES"));
    }

    /** The DTO class and the type-label are two spellings of one fact; a disagreement is a 400. */
    @Test
    void aBodyWhoseTypeAndLabelDisagreeIsRejected() {
        Timeseries ts = new Timeseries();
        ts.setExternalId("odd");
        ts.setName("Odd");
        ts.getLabels().add("DATASET");

        assertThrows(InvalidResourceException.class, () -> nodeService.createFromResource(ts));
    }

    /**
     * Dataset and policy entities must stay orphans: their access rule is the manage grant, and
     * the ACL's write-everything fallback keys on data_set_id being null (see
     * POLICY_DATASETID_BUG.md). A create naming one is refused rather than quietly stripped —
     * the caller was already authorized against that id, so a 201 with the field dropped would
     * report work that never happened. Every other type maps it normally.
     */
    @Test
    void datasetAndPolicyCreatesRejectADataSetId() {
        DataSetRepository repo = mock(DataSetRepository.class);
        when(repo.getReferenceById(7L)).thenReturn(new DatasetEntity());
        NodeService service = new NodeService(labelService, repo);

        Resource dataset = resource("DATASET");
        dataset.setDataSetId(7L);
        Resource policy = resource("POLICY");
        policy.setDataSetId(7L);
        Resource asset = resource("ASSET");
        asset.setDataSetId(7L);

        assertThrows(InvalidResourceException.class, () -> service.createFromResource(dataset));
        assertThrows(InvalidResourceException.class, () -> service.createFromResource(policy));
        assertNotNull(service.createFromResource(asset).getDataSet());
    }

    /**
     * A function, data set, policy or time series is not a navigation root. The legacy flat body
     * always carries {@code isRoot:false}, which means nothing and must keep working; an explicit
     * {@code true} is a request this type cannot honour and is refused rather than dropped.
     */
    @Test
    void aTypeThatCannotBeRootRefusesIsRootTrue() {
        for (String type : new String[]{"FUNCTION", "DATASET", "POLICY"}) {
            Resource body = resource(type);
            body.setIsRoot(true);
            assertThrows(InvalidResourceException.class, () -> nodeService.createFromResource(body),
                    type + " must refuse isRoot=true");
        }
    }

    @Test
    void isRootFalseIsAcceptedOnTypesThatCannotBeRoot() {
        Resource body = resource("FUNCTION");
        body.setIsRoot(false);

        NodeEntity node = nodeService.createFromResource(body);

        assertInstanceOf(FunctionEntity.class, node);
        assertEquals(Boolean.FALSE, node.getIsRoot());
    }

    /** Root-ness still applies where it is legal. */
    @Test
    void anAssetCanStillBeARoot() {
        Resource body = resource("ASSET");
        body.setIsRoot(true);

        assertEquals(Boolean.TRUE, nodeService.createFromResource(body).getIsRoot());
    }

    /** Without a dataSetId the same creates succeed and stay orphans. */
    @Test
    void datasetAndPolicyCreatesStayOrphans() {
        assertNull(nodeService.createFromResource(resource("DATASET")).getDataSet());
        assertNull(nodeService.createFromResource(resource("POLICY")).getDataSet());
    }

    @Test
    void functionLabelCreatesFunctionEntity() {
        // Functions are plain datastore nodes now: the FUNCTION type-label builds a FunctionEntity
        // through the shared resource pipeline, just like ASSET/DATASET/POLICY.
        NodeEntity node = nodeService.createFromResource(resource("FUNCTION"));
        assertInstanceOf(FunctionEntity.class, node);
    }
}
