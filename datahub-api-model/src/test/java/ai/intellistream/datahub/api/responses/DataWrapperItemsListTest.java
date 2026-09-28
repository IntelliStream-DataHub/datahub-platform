// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.api.responses;

import org.junit.jupiter.api.Test;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * {@code items} is a {@link List}, and the three things that had to keep working when it stopped
 * being a {@code Collection}: a caller may still hand over a set, a list it hands over is still the
 * one the envelope holds, and {@code "items": null} is still null rather than empty.
 *
 * <p>The narrowing is what lets a caller read the first of one result by position. Reading it
 * through {@code getItems().iterator().next()} was the only way while this was a {@code Collection},
 * and every SDK example that reads a single series had to do exactly that.
 */
class DataWrapperItemsListTest {

    private final JsonMapper mapper = JsonMapper.builder().build();

    @Test
    void itemsIsAListAndIsAddressableByPosition() {
        DataWrapper<String> wrapper = new DataWrapper<String>().setItems(List.of("first", "second"));

        assertInstanceOf(List.class, wrapper.getItems());
        assertEquals("first", wrapper.getItems().get(0));
        assertEquals("second", wrapper.getItems().get(1));
    }

    @Test
    void aListIsHeldAsGivenRatherThanCopied() {
        // Callers that mutate the list they passed still see the envelope change, as before.
        List<String> given = new ArrayList<>(List.of("a"));
        DataWrapper<String> wrapper = new DataWrapper<String>().setItems(given);

        assertSame(given, wrapper.getItems());
        given.add("b");
        assertEquals(List.of("a", "b"), wrapper.getItems());
    }

    @Test
    void aSetIsAcceptedAndKeepsItsIterationOrder() {
        // The graph endpoints hand over sets; DataWrapper still takes any Collection.
        DataWrapper<String> wrapper =
                new DataWrapper<String>().setItems(new LinkedHashSet<>(List.of("x", "y", "z")));

        assertEquals(List.of("x", "y", "z"), wrapper.getItems());
    }

    @Test
    void nullItemsStayNull() {
        // Several request validators test `getItems() == null` to reject a body that sent
        // "items": null, so an absent array must not arrive as an empty one.
        assertNull(new DataWrapper<String>().setItems(null).getItems());

        DataWrapper<String> parsed = mapper.readValue(
                "{\"items\":null}", new TypeReference<DataWrapper<String>>() {});
        assertNull(parsed.getItems());
    }

    @Test
    void theWireShapeIsUnchanged() {
        DataWrapper<String> wrapper = new DataWrapper<String>().setItems(List.of("x", "y"));
        assertEquals("{\"items\":[\"x\",\"y\"]}", mapper.writeValueAsString(wrapper));

        DataWrapper<String> parsed = mapper.readValue(
                "{\"items\":[\"x\",\"y\"],\"nextCursor\":\"c1\"}",
                new TypeReference<DataWrapper<String>>() {});
        assertEquals(List.of("x", "y"), parsed.getItems());
        assertEquals("c1", parsed.getNextCursor());
    }
}
