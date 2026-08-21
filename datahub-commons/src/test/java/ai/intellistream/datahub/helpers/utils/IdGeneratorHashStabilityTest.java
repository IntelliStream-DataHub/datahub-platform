// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.helpers.utils;

import org.junit.jupiter.api.Test;

import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins the derived ids that get written to columns.
 *
 * <p>{@code xxHash} backs {@code inode.path_hash}, {@code unit.external_id_hash},
 * {@code relationship_type.hash} and the CDC and governance-template hashes.
 * {@code generate128bitKey} backs the ClickHouse {@code events.external_id_hash} and the KVRocks
 * keys. Each is computed once at write and compared on every read, so changing one orphans rows
 * without any error surfacing — the read simply finds nothing.
 *
 * <p>Two of those have already gone wrong. The label hash silently moved from XXH64 to XXH3 and
 * left every pre-2026-07-20 row unfindable. The event filter reached for the node hash instead of
 * the 128-bit tenant-salted one and matched nothing at all. Neither failed a test, because nothing
 * asserted what the values were.
 *
 * <p>If one of these fails, the change needs a migration that rehashes the column, in the same
 * commit — not a new expected value here.
 */
class IdGeneratorHashStabilityTest {

    @Test
    void xxHashIsStable() {
        assertEquals(4521575793367839573L, IdGenerator.xxHash("pump_a_01"));
        assertEquals(4826030794328641802L, IdGenerator.xxHash("/org-a/team-b"));
    }

    /**
     * {@code xxHash} is XXH3 and hashes verbatim — it does <em>not</em> lowercase. Callers that need
     * case-insensitivity go through {@code ExternalIds.hash} or {@code Labels.hash}, which
     * normalise first; that split is the reason the label hash could drift unnoticed.
     */
    @Test
    void xxHashDoesNotNormaliseItsInput() {
        assertEquals(false, IdGenerator.xxHash("PUMP_A_01") == IdGenerator.xxHash("pump_a_01"));
    }

    /**
     * The event key: BLAKE3 over {@code text + tenant}, 128 bits, unsigned. The tenant is part of
     * the input, so the same external id in two tenants is two different keys.
     */
    @Test
    void the128BitKeyIsStableAndTenantSalted() {
        assertEquals(new BigInteger("262895099570298928685201221067974207947"),
                IdGenerator.generate128bitKey("work_order_4711", "acme"));
        assertEquals(false,
                IdGenerator.generate128bitKey("work_order_4711", "acme")
                        .equals(IdGenerator.generate128bitKey("work_order_4711", "other")));
    }



    @Test
    void theDeterministicUuidIsStable() {
        assertEquals("c5c7c123-9a65-120c-7fbb-d5a36a8169cb",
                IdGenerator.deterministicUUID("work_order_4711", "acme").toString());
    }
}
