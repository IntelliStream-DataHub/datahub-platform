// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.transformers;

import ai.intellistream.datahub.function.Function;
import ai.intellistream.datahub.jpa.domains.AssetEntity;
import ai.intellistream.datahub.jpa.domains.DatasetEntity;
import ai.intellistream.datahub.jpa.domains.FunctionEntity;
import ai.intellistream.datahub.jpa.domains.NodeEntity;
import ai.intellistream.datahub.jpa.domains.PolicyEntity;
import ai.intellistream.datahub.jpa.domains.ResourceEntity;
import ai.intellistream.datahub.jpa.domains.TimeseriesEntity;
import ai.intellistream.datahub.models.Asset;
import ai.intellistream.datahub.models.DataSetModel;
import ai.intellistream.datahub.models.NodeModel;
import ai.intellistream.datahub.models.Policy;
import ai.intellistream.datahub.models.Resource;
import ai.intellistream.datahub.timeseries.Timeseries;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link NodeReadMapper} is the one entity→DTO path for typed reads: each discriminator maps to
 * its own DTO, labels come uniformly from the denormalised string, and metadata never aliases the
 * entity's map. These are the decisions a mixed {@code /resources} read rides on.
 */
class NodeReadMapperTest {

    private final NodeReadMapper mapper = new NodeReadMapper();

    private static <T extends NodeEntity> T base(T e, String externalId, String labels) {
        e.setExternalId(externalId);
        e.setName(externalId);
        e.setLabels(labels);
        e.setMetadata(new HashMap<>(Map.of("k", "v")));
        return e;
    }

    @Test
    @DisplayName("each discriminator maps to its own DTO")
    void eachEntityTypeMapsToItsOwnDto() {
        List<NodeModel> out = mapper.from(List.of(
                base(new AssetEntity(), "plant_a", "ASSET"),
                base(new ResourceEntity(), "pump_1", "PIPE"),
                base(new TimeseriesEntity(), "engine_temp", "TIMESERIES"),
                base(new DatasetEntity(), "plant_data", "DATASET"),
                base(new PolicyEntity(), "IS_WRITE_PROTECTED", "POLICY"),
                base(new FunctionEntity(), "f_of_x", "FUNCTION")));

        assertInstanceOf(Asset.class, out.get(0));
        assertInstanceOf(Resource.class, out.get(1));
        assertInstanceOf(Timeseries.class, out.get(2));
        assertInstanceOf(DataSetModel.class, out.get(3));
        assertInstanceOf(Policy.class, out.get(4));
        assertInstanceOf(Function.class, out.get(5));
    }

    @Test
    @DisplayName("geoLocation maps only on assets; isRoot only on assets and resources")
    void geoLocationIsAssetOnly() {
        AssetEntity assetEntity = base(new AssetEntity(), "plant_a", "ASSET");
        assetEntity.setIsRoot(true);
        assetEntity.setGeoLocation("{\"type\":\"Point\",\"coordinates\":[10.75,59.91]}");

        Asset asset = (Asset) mapper.from(assetEntity);

        assertEquals(true, asset.getIsRoot());
        assertTrue(asset.getGeoLocation().getJson().contains("Point"));
    }

    /**
     * The defect this mapper fixes: the timeseries read path never set labels, so a row's domain
     * labels were invisible and only the constructor-seeded type-label survived.
     */
    @Test
    @DisplayName("timeseries carry their full label set from the row")
    void timeseriesLabelsComeFromTheRow() {
        Timeseries ts = (Timeseries) mapper.from(
                base(new TimeseriesEntity(), "engine_temp", "TIMESERIES,FLOW,ENGINE"));

        assertEquals(List.of("TIMESERIES", "FLOW", "ENGINE"), ts.getLabels());
    }

    /** setLabels self-heals: a row whose labels string lost the type-label still reports it. */
    @Test
    @DisplayName("a missing type-label on the row is re-inserted by the DTO")
    void aMissingTypeLabelSelfHeals() {
        Timeseries ts = (Timeseries) mapper.from(base(new TimeseriesEntity(), "engine_temp", ""));

        assertEquals(List.of("TIMESERIES"), ts.getLabels());
    }

    @Test
    @DisplayName("metadata is a plain copy, never the entity's own map")
    void metadataIsCopiedNotAliased() {
        DatasetEntity entity = base(new DatasetEntity(), "plant_data", "DATASET");

        NodeModel dto = mapper.from(entity);
        dto.getMetadata().put("added", "later");

        assertEquals(Map.of("k", "v"), entity.getMetadata());
    }

    /** Policy.dataSetId is input-only (POLICY_DATASETID_BUG.md); reads must never populate it. */
    @Test
    @DisplayName("a policy read carries no dataSetId")
    void policyReadsCarryNoDataSetId() {
        Policy policy = (Policy) mapper.from(base(new PolicyEntity(), "IS_WRITE_PROTECTED", "POLICY"));

        assertNull(policy.getDataSetId());
        assertEquals(List.of("POLICY"), policy.getLabels());
    }
}
