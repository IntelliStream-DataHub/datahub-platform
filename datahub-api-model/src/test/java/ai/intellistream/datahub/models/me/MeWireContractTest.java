// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.models.me;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Pins the wire shape of the {@code /me} answers, which the flow service and the SDK read back. */
class MeWireContractTest {

    private final JsonMapper mapper = JsonMapper.builder().build();

    @Test
    @SuppressWarnings("unchecked")
    void grantsWireShapeIsStable() {
        MeGrants grants = new MeGrants(true, List.of("/datasets/*/read", "/flows/write"));

        String json = mapper.writeValueAsString(grants);
        Map<String, Object> m = mapper.readValue(json, Map.class);

        assertEquals(Map.of("admin", true, "groups", List.of("/datasets/*/read", "/flows/write")), m);
        assertEquals(grants, mapper.readValue(json, MeGrants.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void dataSetIdsGoOutAsStringsAndReadBackAsLongs() {
        long beyondDoublePrecision = 9_007_199_254_740_993L;
        MeDataSets dataSets = new MeDataSets(false, List.of(5L, beyondDoublePrecision));

        String json = mapper.writeValueAsString(dataSets);
        Map<String, Object> m = mapper.readValue(json, Map.class);

        assertEquals(Map.of("all", false, "ids", List.of("5", "9007199254740993")), m);
        assertEquals(dataSets, mapper.readValue(json, MeDataSets.class));
    }
}
