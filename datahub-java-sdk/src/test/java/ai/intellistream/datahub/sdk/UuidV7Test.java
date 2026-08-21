// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.sdk;

import ai.intellistream.datahub.sdk.util.UuidV7;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class UuidV7Test {

    @Test
    void generatesValidVersion7Variant2() {
        UUID u = UuidV7.generate();
        assertEquals(7, u.version());
        assertEquals(2, u.variant()); // IETF variant
        assertEquals(u, UUID.fromString(u.toString())); // round-trips as a standard UUID string
    }

    @Test
    void idsAreDistinct() {
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < 10_000; i++) {
            ids.add(UuidV7.generateString());
        }
        assertEquals(10_000, ids.size());
    }
}
