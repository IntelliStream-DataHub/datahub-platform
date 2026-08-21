// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.services;

import ai.intellistream.datahub.errors.InvalidResourceException;
import ai.intellistream.datahub.jpa.domains.AssetEntity;
import ai.intellistream.datahub.jpa.domains.DatasetEntity;
import ai.intellistream.datahub.jpa.domains.FunctionEntity;
import ai.intellistream.datahub.jpa.domains.Label;
import ai.intellistream.datahub.jpa.domains.NodeEntity;
import ai.intellistream.datahub.jpa.domains.PolicyEntity;
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

    @Test
    void timeseriesLabelIsRejectedOnResourceApi() {
        assertThrows(InvalidResourceException.class,
                () -> nodeService.createFromResource(resource("TIMESERIES")));
    }

    @Test
    void functionLabelCreatesFunctionEntity() {
        // Functions are plain datastore nodes now: the FUNCTION type-label builds a FunctionEntity
        // through the shared resource pipeline, just like ASSET/DATASET/POLICY.
        NodeEntity node = nodeService.createFromResource(resource("FUNCTION"));
        assertInstanceOf(FunctionEntity.class, node);
    }
}
