// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.transformers;

import ai.intellistream.datahub.jpa.domains.PolicyEntity;
import ai.intellistream.datahub.jpa.domains.TimeseriesEntity;
import ai.intellistream.datahub.models.DataSetModel;
import ai.intellistream.datahub.models.Policy;
import ai.intellistream.datahub.models.Resource;
import ai.intellistream.datahub.timeseries.Timeseries;
import org.junit.jupiter.api.Test;

import java.util.HashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@code source} is common to all node types, so every non-resource projection must surface it.
 */
class NodeSourceProjectionTest {

    @Test
    void dataSetModelSurfacesSource() {
        Resource r = new Resource();
        r.setName("ds");
        r.setExternalId("ds_a");
        r.setSource("scada");
        r.setMetadata(new HashMap<>());

        DataSetModel ds = DataSetTransformer.toDataSetModel(r);
        assertEquals("scada", ds.getSource());

        // ...and back the other way (write path), so create carries it through mapCommonNodeFields.
        assertEquals("scada", DataSetTransformer.toResource(ds).getSource());
    }

    @Test
    void policySurfacesSource() {
        PolicyEntity node = new PolicyEntity();
        node.setName("test_policy");
        node.setExternalId("pol_a");
        node.setSource("governance");

        Policy p = PolicyTransformer.toPolicy(node);
        assertEquals("governance", p.getSource());
    }

    @Test
    void timeseriesSurfacesSource() {
        TimeseriesEntity node = new TimeseriesEntity();
        node.setName("ts");
        node.setExternalId("ts_a");
        node.setSource("sensor");

        Timeseries ts = TimeseriesTransformer.from(node);
        assertEquals("sensor", ts.getSource());
    }
}
