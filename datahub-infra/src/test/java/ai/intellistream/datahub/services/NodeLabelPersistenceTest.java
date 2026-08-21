// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.services;

import ai.intellistream.datahub.jpa.domains.Label;
import ai.intellistream.datahub.jpa.domains.NodeEntity;
import ai.intellistream.datahub.jpa.domains.PolicyEntity;
import ai.intellistream.datahub.models.Resource;
import ai.intellistream.datahub.repositories.node.DataSetRepository;
import ai.intellistream.datahub.timeseries.Timeseries;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.HashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A node's labels live in two places: the denormalised {@code labels} string and the
 * {@code node_labels} M2M rows. Only the rows are joinable, so a writer that sets the string alone
 * yields a node that reports its labels on every read and matches no label filter — which is what
 * the policy create path did.
 */
class NodeLabelPersistenceTest {

    private NodeService nodeService;

    @BeforeEach
    void setUp() {
        LabelService labelService = mock(LabelService.class);
        nodeService = new NodeService(labelService, mock(DataSetRepository.class));
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
        r.setMetadata(new HashMap<>());
        return r;
    }

    private static void assertBothRepresentations(NodeEntity node, List<String> expected) {
        assertEquals(expected, List.of(node.getLabels().split(",")), "labels string");
        assertNotNull(node.getLabelEntities(), "label entities must be attached, not left null");
        assertEquals(expected, node.getLabelEntities().stream().map(Label::getName).toList(),
                "node_labels rows");
    }

    @ParameterizedTest
    @ValueSource(strings = {"ASSET", "DATASET", "POLICY", "FUNCTION"})
    void everyCreateBranchWritesBothRepresentations(String typeLabel) {
        NodeEntity node = nodeService.createFromResource(resource(typeLabel, "PUMP"));
        assertBothRepresentations(node, List.of(typeLabel, "PUMP"));
    }

    @Test
    void aNodeWithNoTypeLabelWritesBothRepresentations() {
        assertBothRepresentations(nodeService.createFromResource(resource("PUMP", "CRITICAL")),
                List.of("PUMP", "CRITICAL"));
    }

    @Test
    void timeseriesWriteBothRepresentations() {
        Timeseries ts = new Timeseries();
        ts.setExternalId("some_ts");
        ts.setName("Some TS");
        ts.setLabels(List.of("PUMP"));

        assertBothRepresentations(nodeService.mapNewNodeFromTimeseries(ts), List.of("PUMP", "TIMESERIES"));
    }

    /**
     * The policy create path builds its entity by hand rather than going through
     * {@code createFromResource}, so it needs its own guard: it is the site that drifted.
     */
    @Test
    void applyLabelNamesResolvesAndWritesBoth() {
        PolicyEntity policy = new PolicyEntity();
        nodeService.applyLabelNames(policy, List.of("POLICY"));

        assertBothRepresentations(policy, List.of("POLICY"));
    }
}
