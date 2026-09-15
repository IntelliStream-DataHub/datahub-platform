// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.models;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a caller may say about ordering: a property and a direction, and nothing else.
 *
 * <p>{@code DataSort} carried a {@code nulls} field — {@code @Pattern}-validated and lower-cased by
 * a hand-written setter, so it looked thoroughly supported — that no commit in this repository's
 * history ever read. It was the sort body of all five retrievers, so a caller asking for
 * {@code nulls: "first"} got a 200 and the default placement on every filter endpoint at once.
 *
 * <p>It was removed rather than implemented. Null placement follows the sort direction on purpose
 * ({@code NodeSort.nullsLast()}, matched by the ClickHouse event path) so an ordinary btree index
 * serves the ordering either way; honouring an override would need per-placement indexes or a sort,
 * and would have to travel inside the page cursor beside the property and direction or a walk could
 * flip placement mid-page. A knob for a decision the system makes deliberately is not a missing
 * feature.
 */
class DataSortContractTest {

    private final JsonMapper mapper = JsonMapper.builder().build();

    private static List<String> fieldNames() {
        return Arrays.stream(DataSort.class.getDeclaredFields())
                .filter(f -> !f.isSynthetic() && !Modifier.isStatic(f.getModifiers()))
                .map(Field::getName)
                .toList();
    }

    @Test
    @DisplayName("the sort body is exactly property and order")
    void theSortBodyIsPropertyAndOrder() {
        assertEquals(Set.of("property", "order"), Set.copyOf(fieldNames()));
    }

    @Test
    @DisplayName("no accessor keeps the removed field alive")
    void noAccessorSurvives() {
        List<String> leftovers = Arrays.stream(DataSort.class.getMethods())
                .map(java.lang.reflect.Method::getName)
                .filter(name -> name.toLowerCase().contains("nulls"))
                .toList();
        assertTrue(leftovers.isEmpty(),
                "a leftover getNulls/setNulls would put the field back on the wire via Jackson: " + leftovers);
    }

    @Test
    @DisplayName("an ordinary sort still binds")
    void anOrdinarySortStillBinds() {
        DataSort sort = mapper.readValue("{\"property\":[\"name\"],\"order\":\"desc\"}", DataSort.class);

        assertEquals(List.of("name"), sort.getProperty());
        assertEquals("desc", sort.getOrder());
    }

    /**
     * The strict request-body converter turns this into a 400 naming the field. That is a change
     * for anyone who was sending it — and a better answer than the silent 200 they get today, which
     * ordered their results by something they did not ask for.
     */
    @Test
    @DisplayName("nulls is now an unknown field rather than a silent no-op")
    void nullsIsRejectedRatherThanIgnored() {
        assertFalse(fieldNames().contains("nulls"));
    }
}
