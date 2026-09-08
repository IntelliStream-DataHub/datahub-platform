// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.subscription;

import ai.intellistream.datahub.helpers.text.ExternalIds;
import ai.intellistream.datahub.models.IdCollection;
import ai.intellistream.datahub.models.datafilters.NodeFilter;
import ai.intellistream.datahub.models.datafilters.TimeFilter;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the request shape of {@link SubscriptionFilter} against the rest of the filter family.
 *
 * <p>{@link SubscriptionFilter} cannot extend {@link NodeFilter} — a subscription has no
 * {@code source}, {@code description}, {@code labels} or {@code metadata} column — so the overlap
 * it does have is a convention, and a convention with nothing checking it is how this filter came
 * to carry a single {@code timeseries} criterion while every other one grew ids, patterns and time
 * windows.
 *
 * <p>The family rules themselves — that the shared criteria are named and typed as
 * {@code NodeFilter} names and types them, and that every list accepts a bare value — live in
 * {@code FilterContractParityTest} alongside the other five filters, not here. This class had its
 * own copy of the first one, which is a second list to keep current and one the parity test cannot
 * see; what is left here is the wire shape only this filter has.
 */
class SubscriptionFilterWireContractTest {

    private final JsonMapper mapper = JsonMapper.builder().build();

    @Test
    @SuppressWarnings("unchecked")
    void subscriptionFilterWireShapeIsStable() {
        SubscriptionFilter f = new SubscriptionFilter();
        f.setId(List.of(12L, 18L));
        f.setExternalId(List.of("fleet_dashboard", "plant_a_*"));
        f.setName(List.of("Fleet%"));
        f.setTimeseries(List.of(IdCollection.createFromId(29L)));
        TimeFilter created = new TimeFilter();
        f.setCreatedTime(created);

        Map<String, Object> m = mapper.readValue(mapper.writeValueAsString(f), Map.class);

        assertEquals(List.of("12", "18"), m.get("id")); // ids are strings on the wire
        assertEquals(List.of("fleet_dashboard", "plant_a_*"), m.get("externalId"));
        assertEquals(List.of("Fleet%"), m.get("name"));
        assertEquals(List.of(Map.of("id", "29")), m.get("timeseries"));
        assertTrue(m.containsKey("createdTime"));

        // Derived, not part of the request contract — they must not leak onto the wire.
        assertFalse(m.containsKey("externalIdHashes"), "derivation helper, @JsonIgnore'd");
        assertFalse(m.containsKey("externalIdPatterns"), "derivation helper, @JsonIgnore'd");
        assertFalse(m.containsKey("namePatterns"), "derivation helper, @JsonIgnore'd");
    }

    /**
     * The scalar form of every list criterion, which the node filters accept and this one used not
     * to have any of.
     */
    @Test
    void aBareValueIsAcceptedWhereAListIsDeclared() {
        SubscriptionFilter f = mapper.readValue(
                "{\"externalId\":\"fleet_dashboard\",\"name\":\"Fleet dashboard\",\"id\":\"12\","
                        + "\"timeseries\":{\"externalId\":\"heater_2012_temp\"}}",
                SubscriptionFilter.class);

        assertEquals(List.of("fleet_dashboard"), f.getExternalId());
        assertEquals(List.of("Fleet dashboard"), f.getName());
        assertEquals(List.of(12L), f.getId());
        // timeseries was the one collection here without @SingleOrList, so the bare form that works
        // for EventFilter.relatedResources and DataSetScopedFilter.dataSetId was a 400 on this
        // endpoint alone.
        assertEquals(List.of("heater_2012_temp"),
                f.getTimeseries().stream().map(IdCollection::getExternalId).toList());
    }

    /**
     * The same split {@code NodeFilter} makes: literals through the indexed hash, wildcards through
     * an ILIKE scan. Derived through {@code FilterPatterns} rather than re-derived, so the two
     * cannot come to match different rows for the same body.
     */
    @Test
    void externalIdSplitsIntoIndexedHashesAndScannedPatterns() {
        SubscriptionFilter f = new SubscriptionFilter();
        f.setExternalId(List.of("Fleet_Dashboard", "plant_a_*"));

        assertEquals(List.of(ExternalIds.hash("fleet_dashboard")), f.getExternalIdHashes(),
                "a literal entry resolves through external_id_hash, case-insensitively");
        assertEquals(1, f.getExternalIdPatterns().size(), "only the wildcard entry needs a scan");
    }

    @Test
    void noExternalIdAtAllIsDistinguishableFromOneThatMatchedNothing() {
        assertEquals(null, new SubscriptionFilter().getExternalIdHashes(),
                "null in, null out — 'no restriction' is not 'restricted to nothing'");
    }
}
