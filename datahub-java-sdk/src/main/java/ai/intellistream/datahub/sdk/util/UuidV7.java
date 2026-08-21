// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.sdk.util;

import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Generates RFC 9562 UUID version 7 (time-ordered) values, dependency-free. Used to stamp a stable id
 * on events before their first send so a retry carries the same id and the backend's ReplacingMergeTree
 * collapses the duplicate.
 *
 * <p><b>Use v7, not a random UUID.</b> The events table is {@code ENGINE = ReplacingMergeTree ORDER BY
 * id} (id is both the sort key and the primary key). A v7's leading 48-bit timestamp means new ids are
 * monotonically increasing, so inserts append at the end of the sort order: sequential writes, a tight
 * primary index, good cache locality and effective part/granule pruning. A random v4 id scatters writes
 * across the whole keyspace, which bloats merges, hurts compression and can badly degrade query and
 * insert performance. If you supply your own event id, supply a time-ordered one.
 */
public final class UuidV7 {

    private UuidV7() {
    }

    public static UUID generate() {
        long ts = System.currentTimeMillis() & 0xFFFF_FFFF_FFFFL; // 48-bit unix_ts_ms
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        long msb = (ts << 16) | (0x7L << 12) | (rnd.nextLong() & 0x0FFFL);   // ts | version 7 | rand_a
        long lsb = (0x2L << 62) | (rnd.nextLong() & 0x3FFF_FFFF_FFFF_FFFFL); // variant 0b10 | rand_b
        return new UUID(msb, lsb);
    }

    public static String generateString() {
        return generate().toString();
    }
}
