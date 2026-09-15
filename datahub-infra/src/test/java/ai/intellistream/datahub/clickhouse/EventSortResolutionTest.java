// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.clickhouse;

import ai.intellistream.datahub.models.DataSort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Resolving a request's {@code sort} to the order the query actually runs in.
 *
 * <p>Replaces {@code EventCursorTest}: the cursor is a wire type now ({@code PageCursor}, tested
 * beside it in api-model), so what is left here is the part that stayed in the query layer —
 * which column a sort names, and whether that column can be paged at all.
 */
class EventSortResolutionTest {

    private static DataSort sortBy(String order, String... properties) {
        DataSort sort = new DataSort();
        sort.setProperty(List.of(properties));
        sort.setOrder(order);
        return sort;
    }

    @Test
    void anAbsentSortIsEventTimeAscending() {
        // The order the keyset pages in, so paging cannot change it.
        assertEquals(ClickHouseEventService.EventSortSpec.DEFAULT,
                ClickHouseEventService.resolveSort(null));
        assertEquals(ClickHouseEventService.EventSortSpec.DEFAULT,
                ClickHouseEventService.resolveSort(new DataSort()));
    }

    @Test
    void aKnownPropertyResolvesToItsColumn() {
        var spec = ClickHouseEventService.resolveSort(sortBy("desc", "createdTime"));

        assertEquals("createdTime", spec.property());
        assertEquals("date_created", spec.column());
        assertTrue(spec.descending());
    }

    @ParameterizedTest
    @ValueSource(strings = {"asc", "ASC", "", "nonsense"})
    void anythingButDescIsAscending(String order) {
        // A malformed order degrades to the documented default rather than silently reversing the
        // results, which is the kind of wrong that looks like missing data.
        assertFalse(ClickHouseEventService.resolveSort(sortBy(order, "eventTime")).descending());
    }

    @Test
    void anUnknownPropertyFallsBackToTheDefault() {
        assertEquals(ClickHouseEventService.EventSortSpec.DEFAULT,
                ClickHouseEventService.resolveSort(sortBy("asc", "noSuchColumn")));
    }

    /**
     * Surrounding whitespace does not make a property unrecognised, and the trimmed name is what
     * the cursor is then minted under.
     *
     * <p>The same fix landed on {@code NodeSort.resolve} and {@code SubscriptionSort.resolve} and
     * never reached here, so one request body sorted a resource or subscription query and silently
     * defaulted an event one. Silent is the problem: the caller gets a 200 and a page ordered by
     * something they did not ask for.
     */
    @Test
    void surroundingWhitespaceIsNotWhatMakesAPropertyUnrecognised() {
        var spec = ClickHouseEventService.resolveSort(sortBy("desc", " source "));

        assertEquals("source", spec.property(), "the property is recognised despite the padding");
        assertEquals("source", spec.column());
        assertTrue(spec.descending());
    }

    /**
     * The trimmed spelling has to be what the spec carries, not the caller's. It is what the cursor
     * encodes and what {@code EventService.assertCursorIsUsable} compares a returned cursor
     * against, so a stray space would otherwise make a cursor unusable on the next page of its own
     * walk.
     */
    @Test
    void theTrimmedSpellingIsWhatTheCursorWillCarry() {
        // Deliberately not eventTime: that is the default, so both sides would agree even when the
        // padded one had fallen through to it, and the test would pass on the broken code.
        assertEquals(ClickHouseEventService.resolveSort(sortBy("asc", "status")).property(),
                ClickHouseEventService.resolveSort(sortBy("asc", "  status  ")).property());
    }

    @Test
    void theFirstRecognisedPropertyWins() {
        // One column, for now: the cursor encodes a single position, and a multi-column position
        // is a tuple comparison with a direction per column.
        var spec = ClickHouseEventService.resolveSort(sortBy("asc", "noSuchColumn", "source", "type"));

        assertEquals("source", spec.property());
    }

    @Test
    void everySortablePropertyCanBePaged() {
        // sub_type and status are Nullable in ClickHouse, and used to be excluded from paging for
        // that reason. The exclusion was worse than the problem: no cursor was produced, so a walk
        // sorted by subType returned one page and stopped, and a client reading "no nextCursor" as
        // "no more rows" lost the rest silently. The null block is handled instead.
        assertTrue(ClickHouseEventService.supportsCursor("eventTime"));
        assertTrue(ClickHouseEventService.supportsCursor("externalId"));
        assertTrue(ClickHouseEventService.supportsCursor("subType"));
        assertTrue(ClickHouseEventService.supportsCursor("status"));
        assertFalse(ClickHouseEventService.supportsCursor("noSuchColumn"));
    }
}
