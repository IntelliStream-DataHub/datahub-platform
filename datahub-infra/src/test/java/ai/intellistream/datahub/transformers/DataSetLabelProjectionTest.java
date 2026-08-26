// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.transformers;

import ai.intellistream.datahub.models.NodeModel;
import ai.intellistream.datahub.models.DataSetModel;
import ai.intellistream.datahub.models.Resource;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The dataset write path used to replace the caller's labels with a bare {@code ["DATASET"]}, so the
 * labels reached neither the node row nor the create response.
 */
class DataSetLabelProjectionTest {

    @Test
    void toResourceKeepsCallerLabelsAlongsideTheTypeLabel() {
        DataSetModel ds = new DataSetModel();
        ds.setName("Plant A telemetry");
        ds.setExternalId("plant_a_telemetry");
        ds.setLabels(List.of("PLANT_A", "TELEMETRY"));

        Resource r = DataSetTransformer.toResource(ds);

        assertTrue(r.getLabels().containsAll(List.of("PLANT_A", "TELEMETRY", "DATASET")), "got " + r.getLabels());
        assertEquals(3, r.getLabels().size(), "got " + r.getLabels());
    }

    @Test
    void toResourceStampsTheTypeLabelOnAnUnlabelledDataSet() {
        DataSetModel ds = new DataSetModel();
        ds.setName("SAP work orders");
        ds.setExternalId("sap_work_orders");

        assertEquals(List.of("DATASET"), DataSetTransformer.toResource(ds).getLabels());
    }

    @Test
    void graphFormNodesCarryTheLabels() {
        DataSetModel ds = new DataSetModel();
        ds.setName("Plant A telemetry");
        ds.setExternalId("plant_a_telemetry");
        ds.setLabels(List.of("PLANT_A"));

        var graph = DataSetTransformer.toGraphForm(List.of(ds), List.of(), List.of());

        assertEquals(1, graph.getNodes().size());
        NodeModel node = graph.getNodes().iterator().next();
        assertTrue(node.getLabels().containsAll(List.of("PLANT_A", "DATASET")), "got " + node.getLabels());
    }

    @Test
    void readBackProjectsWhateverTheNodeRowHolds() {
        Resource r = new Resource();
        r.setName("Plant A telemetry");
        r.setExternalId("plant_a_telemetry");
        r.setLabels(List.of("DATASET", "PLANT_A"));

        assertEquals(List.of("DATASET", "PLANT_A"), DataSetTransformer.toDataSetModel(r).getLabels());
    }
}
