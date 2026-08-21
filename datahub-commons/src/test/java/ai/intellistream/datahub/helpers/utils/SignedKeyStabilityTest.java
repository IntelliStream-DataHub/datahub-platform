// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.helpers.utils;

import org.junit.jupiter.api.Test;

import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the signed 128-bit key to exact values.
 *
 * <p>A round-trip test — write with the production path, read with the production path — proves
 * writer and reader agree. It cannot prove the value is the one already in the database, because
 * both sides move together when the algorithm changes. That is precisely how the label hash drifted
 * from XXH64 to XXH3 with every test still green.
 *
 * <p>So the literal matters. events.external_id_hash is Int128 and holds the raw low 128 bits, so
 * these numbers are what is on disk; changing them requires rewriting the column, not editing this
 * file.
 */
class SignedKeyStabilityTest {

    private static final BigInteger TWO_POW_128 = BigInteger.ONE.shiftLeft(128);

    @Test
    void theSignedKeyIsStable() {
        assertEquals(new BigInteger("-77387267350639534778173386363794003509"),
                IdGenerator.generate128bitKeySigned("work_order_4711", "acme"));
    }

    /** Above 2^127 the two forms differ by exactly 2^128 — the case that used to be unreachable. */
    @Test
    void aKeyAboveTheHalfwayPointIsItsNegativeTwin() {
        BigInteger unsigned = IdGenerator.generate128bitKey("work_order_4711", "acme");
        assertTrue(unsigned.testBit(127), "this fixture must sit above 2^127 to be the interesting case");
        assertEquals(unsigned.subtract(TWO_POW_128),
                IdGenerator.generate128bitKeySigned("work_order_4711", "acme"));
    }

    /** Below it the forms agree, which is why the bug only ever hid half the rows. */
    @Test
    void aKeyBelowItIsUnchanged() {
        for (String id : new String[]{"a", "b", "c", "d", "e", "f", "g", "h"}) {
            BigInteger unsigned = IdGenerator.generate128bitKey(id, "acme");
            BigInteger signed = IdGenerator.generate128bitKeySigned(id, "acme");
            assertEquals(unsigned.testBit(127) ? unsigned.subtract(TWO_POW_128) : unsigned, signed,
                    "signed form wrong for " + id);
        }
    }
}
