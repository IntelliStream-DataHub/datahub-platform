// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.clickhouse;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The literal formatting behind {@code Array(String)} and {@code Map(String, String)} query
 * parameters.
 *
 * <p>Worth pinning because the failure is remote and unhelpful. The ClickHouse client renders an
 * unrecognised parameter with {@code toString()}, so a raw {@code List} arrives as {@code [b, c]}
 * and a raw {@code Map} as {@code {k=v}}; the server then rejects the whole mutation with
 * CANNOT_PARSE_QUOTED_STRING, naming the parameter but not the caller. Passing them through these
 * helpers is the only thing keeping event metadata updates working.
 */
class ClickHouseEventParamLiteralsTest {

    @Test
    void stringArraysAreQuotedElementWise() {
        assertEquals("['b','c']", ClickHouseEventService.toChStringArray(List.of("b", "c")));
    }

    @Test
    void stringMapsAreQuotedOnBothSides() {
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("overwrite", "new");
        metadata.put("added", "2");

        assertEquals("{'overwrite':'new','added':'2'}", ClickHouseEventService.toChStringMap(metadata));
    }

    @Test
    void emptyCollectionsStayWellFormed() {
        assertEquals("[]", ClickHouseEventService.toChStringArray(List.of()));
        assertEquals("{}", ClickHouseEventService.toChStringMap(Map.of()));
    }

    /**
     * Metadata is user-supplied, so a key or value containing a quote or a backslash must not be
     * able to terminate the literal early and change the mutation.
     */
    @Test
    void quotesAndBackslashesAreEscaped() {
        assertEquals("['it\\'s']", ClickHouseEventService.toChStringArray(List.of("it's")));
        assertEquals("['back\\\\slash']", ClickHouseEventService.toChStringArray(List.of("back\\slash")));
        assertEquals("{'it\\'s':'a\\\\b'}", ClickHouseEventService.toChStringMap(Map.of("it's", "a\\b")));
    }
}
