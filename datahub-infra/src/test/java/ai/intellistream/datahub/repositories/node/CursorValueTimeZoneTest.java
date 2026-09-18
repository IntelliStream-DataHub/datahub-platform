// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.repositories.node;

import ai.intellistream.datahub.jpa.domains.DatasetEntity;
import ai.intellistream.datahub.jpa.domains.NodeEntity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.time.ZoneId;
import java.util.TimeZone;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A cursor's timestamp is an absolute instant, not a local time.
 *
 * <p>Carried over from {@code EventCursorTest}, which asserted the same thing about the cursor it
 * parsed: "the value a caller sends back is the instant they were given rather than one shifted by
 * whatever zone the server happens to run in". That test went when its cursor type did, and this is
 * the assertion worth keeping — a walk whose boundary drifts by the server's offset skips or
 * repeats a few hours of rows, and only on machines configured differently from the one it was
 * written on.
 */
class CursorValueTimeZoneTest {

    private final TimeZone original = TimeZone.getDefault();

    @AfterEach
    void restoreZone() {
        TimeZone.setDefault(original);
    }

    private static String createdTimeCursorValue(Instant created) {
        NodeEntity node = new DatasetEntity();
        node.setDateCreated(created.atZone(ZoneId.systemDefault()));
        return NodePredicateBuilder.cursorValue(node, new NodeSort("createdTime", "dateCreated", true));
    }

    @Test
    void theBoundaryIsTheSameEpochMicrosInAnyServerZone() {
        Instant moment = Instant.parse("2026-04-22T14:30:00Z");
        String expected = String.valueOf(ChronoUnit.MICROS.between(Instant.EPOCH, moment));

        for (String zone : new String[]{"UTC", "America/Los_Angeles", "Asia/Kathmandu", "Pacific/Chatham"}) {
            TimeZone.setDefault(TimeZone.getTimeZone(zone));
            assertEquals(expected, createdTimeCursorValue(moment),
                    "cursor boundary shifted in " + zone + "; a walk would skip or repeat rows there");
        }
    }

    /**
     * The boundary keeps the microseconds the column stores.
     *
     * <p>It used to be encoded as epoch millis, which put it strictly below every row in its own
     * millisecond. {@code keyset()} compares {@code column = boundary} to engage the id
     * tie-break, and that equality could never hold against a truncated value — so rows sharing
     * the boundary's millisecond were dropped descending and repeated ascending.
     */
    @Test
    void theBoundaryKeepsMicrosecondsRatherThanTruncatingToMillis() {
        Instant moment = Instant.parse("2026-04-22T14:30:53.563456Z");

        assertEquals("1776868253563456", createdTimeCursorValue(moment),
                "boundary lost its microseconds; the keyset tie-break cannot match the row it names");
    }
}
