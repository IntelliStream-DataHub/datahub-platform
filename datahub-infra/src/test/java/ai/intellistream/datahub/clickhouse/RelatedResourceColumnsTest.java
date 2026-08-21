// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.clickhouse;

import ai.intellistream.datahub.helpers.text.ExternalIds;
import ai.intellistream.datahub.models.IdCollection;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The split/join between the API's single {@code relatedResources} list and the three
 * denormalized ClickHouse columns. This is the one place the columns are derived, so it is also
 * the one place that has to guarantee they stay index-aligned.
 */
class RelatedResourceColumnsTest {

    private static IdCollection of(Long id, String externalId) {
        IdCollection entry = new IdCollection();
        entry.setId(id);
        entry.setExternalId(externalId);
        return entry;
    }

    @Test
    void fromSplitsAResolvedListIntoThreeAlignedArrays() {
        var columns = RelatedResourceColumns.from(List.of(of(7L, "pump_7"), of(34L, "sensor_abc")));

        assertEquals(List.of(7L, 34L), columns.ids());
        assertEquals(List.of("pump_7", "sensor_abc"), columns.externalIds());
        assertEquals(List.of(ExternalIds.hash("pump_7"), ExternalIds.hash("sensor_abc")),
                columns.externalIdHashes());
    }

    @Test
    void fromEmitsSentinelsSoAHalfResolvedEntryCannotShiftTheOtherArrays() {
        // Resolution should make this unreachable, but if it ever were, the entry must occupy a
        // slot in all three arrays rather than silently re-pairing everything after it.
        var columns = RelatedResourceColumns.from(Arrays.asList(of(7L, null), of(34L, "sensor_abc")));

        assertEquals(2, columns.ids().size());
        assertEquals(2, columns.externalIds().size());
        assertEquals(2, columns.externalIdHashes().size());
        assertEquals(List.of(7L, 34L), columns.ids());
        assertEquals(List.of("", "sensor_abc"), columns.externalIds());
        assertEquals(List.of(0L, ExternalIds.hash("sensor_abc")), columns.externalIdHashes());
    }

    @Test
    void fromHandlesNullAndEmpty() {
        assertTrue(RelatedResourceColumns.from(null).ids().isEmpty());
        assertTrue(RelatedResourceColumns.from(List.of()).externalIdHashes().isEmpty());
    }

    @Test
    void zipRestoresTheListWrittenByFrom() {
        List<IdCollection> original = List.of(of(7L, "pump_7"), of(34L, "sensor_abc"));
        var columns = RelatedResourceColumns.from(original);

        List<IdCollection> restored = RelatedResourceColumns.zip(columns.ids(), columns.externalIds());

        assertEquals(2, restored.size());
        assertEquals(7L, restored.get(0).getId());
        assertEquals("pump_7", restored.get(0).getExternalId());
        assertEquals(34L, restored.get(1).getId());
        assertEquals("sensor_abc", restored.get(1).getExternalId());
    }

    @Test
    void zipKeepsLeftoversFromRowsWrittenBeforeTheSingleListModel() {
        // The old code back-filled each array independently, so pre-existing rows can have arrays
        // of different lengths and orderings. Those relations must still read back — as
        // single-sided entries — rather than being dropped or throwing.
        List<Long> ids = List.of(7L, 99L, 100L);
        List<String> externalIds = List.of("pump_7");

        List<IdCollection> restored = RelatedResourceColumns.zip(ids, externalIds);

        assertEquals(3, restored.size());
        assertEquals("pump_7", restored.get(0).getExternalId());
        assertEquals(7L, restored.get(0).getId());
        assertEquals(99L, restored.get(1).getId());
        assertNull(restored.get(1).getExternalId());
        assertEquals(100L, restored.get(2).getId());
    }

    @Test
    void zipKeepsUnpairedExternalIdsToo() {
        List<IdCollection> restored = RelatedResourceColumns.zip(List.of(7L), List.of("pump_7", "valve_9"));

        assertEquals(2, restored.size());
        assertEquals(7L, restored.get(0).getId());
        assertEquals("valve_9", restored.get(1).getExternalId());
        assertNull(restored.get(1).getId());
    }

    @Test
    void zipDropsSentinelSlots() {
        // Sentinel 0L / "" mean "this side was never resolved" — they must not surface as data.
        List<IdCollection> restored = RelatedResourceColumns.zip(
                new ArrayList<>(List.of(0L, 34L)), new ArrayList<>(List.of("pump_7", "")));

        assertEquals(2, restored.size());
        assertNull(restored.get(0).getId());
        assertEquals("pump_7", restored.get(0).getExternalId());
        assertEquals(34L, restored.get(1).getId());
        assertNull(restored.get(1).getExternalId());
    }

    @Test
    void zipHandlesNullColumns() {
        assertTrue(RelatedResourceColumns.zip(null, null).isEmpty());
        assertEquals(1, RelatedResourceColumns.zip(null, List.of("pump_7")).size());
    }
}
