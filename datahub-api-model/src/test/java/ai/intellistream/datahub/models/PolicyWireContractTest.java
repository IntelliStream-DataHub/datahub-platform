// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.models;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.databind.json.JsonMapper;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins the exact on-the-wire shape of {@link Policy}, order-independent. Contract net for the
 * {@code NodeModel} re-parent. Uses an already-canonical externalId and explicit timestamps so the pin is
 * stable across the re-parent (which newly adds externalId canonicalization + now()-defaulted timestamps).
 */
class PolicyWireContractTest {

    private final JsonMapper mapper = JsonMapper.builder()
            .disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS)
            .build();

    @Test
    @SuppressWarnings("unchecked")
    void policyWireShapeIsStable() {
        Policy p = new Policy();
        p.setId(5L);
        p.setName("IS_WRITE_PROTECTED");
        p.setDescription("desc");
        p.setType(PolicyType.IS_WRITE_PROTECTED);
        p.setValue("TRUE");
        p.setDeactivated(false);
        p.setExternalId("policy_x");
        p.setNodeType("POLICY");
        p.setMetadata(Map.of("templateId", "3"));
        p.setTemplateId(3L);
        p.setDataSetId(12L);
        p.setSource("src");                              // hoisted onto NodeModel
        p.setCreatedTime(ZonedDateTime.parse("2024-06-17T12:34:56Z"));
        p.setLastUpdatedTime(ZonedDateTime.parse("2024-06-18T00:00:00Z"));

        Map<String, Object> m = mapper.readValue(mapper.writeValueAsString(p), Map.class);

        assertEquals("5", m.get("id"));                  // ToStringSerializer
        assertEquals("IS_WRITE_PROTECTED", m.get("name"));
        assertEquals("desc", m.get("description"));
        assertEquals("IS_WRITE_PROTECTED", m.get("type"));
        assertEquals("TRUE", m.get("value"));
        assertEquals(false, m.get("deactivated"));       // boolean isDeactivated -> "deactivated"
        assertEquals("policy_x", m.get("externalId"));
        assertEquals("POLICY", m.get("nodeType"));
        assertEquals(Map.of("templateId", "3"), m.get("metadata"));
        assertEquals("3", m.get("templateId"));          // ToStringSerializer
        assertEquals("12", m.get("dataSetId"));          // ToStringSerializer
        assertEquals("src", m.get("source"));
        assertEquals(List.of(), m.get("relatedResources"));
        // labels hoisted onto NodeModel; a policy self-types with the POLICY type-label.
        assertEquals(List.of("POLICY"), m.get("labels"));
        assertEquals("2024-06-17T12:34:56Z", m.get("createdTime"));
        assertEquals("2024-06-18T00:00:00Z", m.get("lastUpdatedTime"));

        assertEquals(Set.of("id", "name", "description", "type", "value", "deactivated", "externalId",
                "nodeType", "metadata", "templateId", "dataSetId", "source", "relatedResources", "labels",
                "createdTime", "lastUpdatedTime"),
                m.keySet());
    }
}
