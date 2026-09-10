// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.models;

import ai.intellistream.datahub.function.Function;
import ai.intellistream.datahub.timeseries.Timeseries;
import org.junit.jupiter.api.Test;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the heterogeneous wire contract: a {@code NodeModel}-typed payload dispatches to the right
 * concrete DTO from its <em>labels alone</em> — the type-label is the discriminator, there is no
 * extra type property — with no type-label meaning a plain {@link Resource} and more than one
 * being rejected. This is what a mixed {@code /resources/filter} response rides on, in the api,
 * the Java SDK, and the console's Feign decoder, all of which register {@link NodeModelSubtypes}.
 */
class NodeModelPolymorphicWireTest {

    private final JsonMapper mapper = JsonMapper.builder()
            .addModule(new NodeModelSubtypes())
            .build();

    @Test
    void aMixedListRoundTripsToTheConcreteClasses() {
        Asset asset = new Asset();
        asset.setExternalId("plant_a");
        asset.setGeoLocation(new GeoLocation("{\"type\":\"Point\",\"coordinates\":[10.75,59.91]}"));
        Resource resource = new Resource();
        resource.setExternalId("pump_1");
        Timeseries ts = new Timeseries();
        ts.setExternalId("engine_temp");
        ts.setUnit("Deg C");
        DataSetModel dataset = new DataSetModel();
        dataset.setExternalId("plant_data");
        Policy policy = new Policy();
        policy.setExternalId("policy_x");
        Function function = new Function();
        function.setExternalId("f_of_x");

        String json = mapper.writeValueAsString(List.of(asset, resource, ts, dataset, policy, function));
        List<NodeModel> back = mapper.readValue(json, new TypeReference<List<NodeModel>>() {});

        assertEquals(6, back.size());
        Asset backAsset = assertInstanceOf(Asset.class, back.get(0));
        assertTrue(backAsset.getGeoLocation().getJson().contains("Point"));
        assertInstanceOf(Resource.class, back.get(1));
        Timeseries backTs = assertInstanceOf(Timeseries.class, back.get(2));
        assertEquals("Deg C", backTs.getUnit());
        assertInstanceOf(DataSetModel.class, back.get(3));
        assertInstanceOf(Policy.class, back.get(4));
        assertInstanceOf(Function.class, back.get(5));
    }

    /** No type-label means a plain resource — the one family member without a label of its own. */
    @Test
    void noTypeLabelBindsAsResource() {
        NodeModel bound = mapper.readValue("""
                {"externalId":"pump_1","name":"Pump 1","labels":["PIPE","PLANT_A"]}
                """, NodeModel.class);

        assertInstanceOf(Resource.class, bound);
    }

    /** Dispatch canonicalises the same way the label store does, so case never changes the type. */
    @Test
    void aLowercaseTypeLabelDispatchesTheSame() {
        NodeModel bound = mapper.readValue("""
                {"externalId":"plant_data","name":"Plant data","labels":["dataset"]}
                """, NodeModel.class);

        assertInstanceOf(DataSetModel.class, bound);
    }

    /** One type-label per node is a constraint of the model; ambiguous bodies are rejected. */
    @Test
    void twoTypeLabelsAreRejected() {
        assertThrows(Exception.class, () -> mapper.readValue("""
                {"externalId":"odd","name":"Odd","labels":["DATASET","POLICY"]}
                """, NodeModel.class));
    }

    /**
     * The registry verifies itself: every registered DTO seeds exactly its registry key as the
     * type-label a fresh instance carries, so the map and the DTOs cannot drift apart. (The
     * entity-side authority, {@code TypeLabels} in datahub-infra, is held equal to this map by
     * {@code NodeFamilyParityTest} in datahub-api.)
     */
    @Test
    void everyRegisteredSubtypeSeedsItsOwnKeyAsTypeLabel() throws Exception {
        for (Map.Entry<String, Class<? extends NodeModel>> entry : NodeModelSubtypes.BY_TYPE_LABEL.entrySet()) {
            NodeModel fresh = entry.getValue().getDeclaredConstructor().newInstance();
            assertEquals(List.of(entry.getKey()), fresh.getLabels(),
                    entry.getValue().getSimpleName() + " must seed exactly its registry key");
        }
    }
}
