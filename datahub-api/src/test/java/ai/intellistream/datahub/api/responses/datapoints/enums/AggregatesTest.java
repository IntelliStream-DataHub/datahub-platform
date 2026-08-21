// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.responses.datapoints.enums;

import ai.intellistream.datahub.responses.datapoints.enums.Aggregates;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class AggregatesTest {

    @Test
    void findByDescription() {
        var min = Aggregates.findByDescription("mIn");
        var sum = Aggregates.findByDescription("SUM");
        var max = Aggregates.findByDescription("max");
        var nullInstance = Aggregates.findByDescription("max.");

        assertEquals(min, Aggregates.MIN);
        assertEquals(sum, Aggregates.SUM);
        assertEquals(max, Aggregates.MAX);
        assertNull(nullInstance);
    }
}