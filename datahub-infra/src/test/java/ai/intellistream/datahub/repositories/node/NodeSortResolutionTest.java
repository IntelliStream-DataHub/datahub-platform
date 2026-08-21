// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.repositories.node;

import ai.intellistream.datahub.models.DataSort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The sort property is the one caller-supplied value that chooses a <em>column</em>, which makes it
 * the classic injection surface: a column name cannot be a bound parameter, so if a request body
 * could reach one, no amount of parameter binding elsewhere would help.
 *
 * <p>It cannot — {@link NodeSort#resolve} maps through a fixed whitelist and falls back to the
 * default for anything else. That was true and untested: the events side had this coverage and the
 * node side, added later, had none. Untested is how a whitelist becomes a passthrough during a
 * tidy-up.
 */
class NodeSortResolutionTest {

    private static DataSort sortBy(String order, String... properties) {
        DataSort sort = new DataSort();
        sort.setProperty(List.of(properties));
        sort.setOrder(order);
        return sort;
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "dateCreated; DROP TABLE node; --",
            "name) OR 1=1 --",
            "(SELECT password FROM users)",
            "../../etc/passwd",
            "",
            "   ",
            "NAME",           // right column, wrong case: still not a match
            "date_created"})  // the underlying column, not the public property
    void anythingOutsideTheWhitelistFallsBackToTheDefault(String property) {
        NodeSort resolved = NodeSort.resolve(sortBy("asc", property));

        assertEquals(NodeSort.DEFAULT, resolved,
                "an unrecognised sort property must not reach the query; it resolved to " + resolved);
    }

    @Test
    void aPayloadAmongValidPropertiesDoesNotWin() {
        // The first *recognised* property is taken, not the first supplied — so a payload cannot
        // displace a legitimate one by being listed ahead of it.
        assertEquals("name", NodeSort.resolve(sortBy("asc", "'; DROP TABLE node; --", "name")).property());
    }

    @Test
    void everyWhitelistedPropertyMapsToAnEntityAttribute() {
        for (String property : List.of("id", "externalId", "name", "source", "description",
                "createdTime", "lastUpdatedTime", "dataSetId")) {
            NodeSort resolved = NodeSort.resolve(sortBy("asc", property));
            assertEquals(property, resolved.property(), property + " should be sortable");
            assertFalse(resolved.attribute().isBlank());
        }
    }

    @Test
    void theResolvedAttributeIsNeverCallerText() {
        // Belt and braces: whatever comes back, its attribute is one of the mapped few.
        List<String> attributes = List.of("id", "externalId", "name", "source", "description",
                "dateCreated", "lastUpdated", "dataSet");
        for (String property : List.of("' OR 1=1 --", "name", "noSuchThing", "createdTime")) {
            assertTrue(attributes.contains(NodeSort.resolve(sortBy("desc", property)).attribute()));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"asc", "ASC", "", "nonsense", "desc; DROP TABLE node"})
    void onlyAnExactDescMeansDescending(String order) {
        assertEquals("desc".equalsIgnoreCase(order),
                NodeSort.resolve(sortBy(order, "name")).descending());
    }
}
