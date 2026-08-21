// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.jpa.domains;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link TypeLabels} — the type-label constants and identity mapping. The label-update
 * enforcement itself lives in {@code LabelService.resolveLabelUpdate} and is tested there.
 */
class TypeLabelsTest {

    @Test
    void forEntity_mapsEachEntityTypeToItsTypeLabel() {
        assertEquals(Optional.of("ASSET"), TypeLabels.forEntity(new AssetEntity()));
        assertEquals(Optional.of("DATASET"), TypeLabels.forEntity(new DatasetEntity()));
        assertEquals(Optional.of("POLICY"), TypeLabels.forEntity(new PolicyEntity()));
        assertEquals(Optional.of("TIMESERIES"), TypeLabels.forEntity(new TimeseriesEntity()));
        assertEquals(Optional.of("FUNCTION"), TypeLabels.forEntity(new FunctionEntity()));
    }

    @Test
    void forEntity_plainResourceHasNoTypeLabel() {
        assertEquals(Optional.empty(), TypeLabels.forEntity(new ResourceEntity()));
    }

    @Test
    void isTypeLabel_isCaseInsensitiveAndOnlyTrueForTypeLabels() {
        assertTrue(TypeLabels.isTypeLabel("DATASET"));
        assertTrue(TypeLabels.isTypeLabel("dataset"));
        assertTrue(TypeLabels.isTypeLabel("Asset"));
        assertFalse(TypeLabels.isTypeLabel("PIPE"));
        assertFalse(TypeLabels.isTypeLabel(null));
    }
}
