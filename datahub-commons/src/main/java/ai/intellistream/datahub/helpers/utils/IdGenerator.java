// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.helpers.utils;

import com.fasterxml.uuid.Generators;
import jakarta.validation.constraints.NotNull;
import net.openhft.hashing.LongHashFunction;
import org.apache.commons.codec.digest.Blake3;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Random;
import java.util.UUID;

public class IdGenerator {

    private static final Random RANDOM = new Random();

    public static long getRandomId(){
        return UUID.randomUUID().getMostSignificantBits() & Long.MAX_VALUE;
    }

    public static String getRandomUUID7AsString() {
        return Generators.timeBasedEpochRandomGenerator(RANDOM).generate().toString();
    }

    public static UUID getRandomUUID7() {
        return Generators.timeBasedEpochRandomGenerator(RANDOM).generate();
    }

    public static byte[] getRandomUUID7asBytes() {
        UUID uuid = Generators.timeBasedEpochRandomGenerator(RANDOM).generate();
        ByteBuffer bb = ByteBuffer.wrap(new byte[16]);
        bb.putLong(uuid.getMostSignificantBits());
        bb.putLong(uuid.getLeastSignificantBits());
        return bb.array();
    }

    public static byte[] blake3Hash(String text, int length){
        Blake3 hasher = Blake3.initHash();
        hasher.update(text.getBytes(StandardCharsets.UTF_8));
        byte[] hash = new byte[length];
        hasher.doFinalize(hash);
        return hash;
    }

    /**
     * A UUID derived from {@code text} and {@code tenant} rather than randomly generated, so the
     * same inputs always name the same entity.
     *
     * <p>What this buys is an upsert without a lookup. A caller that can compute the id of the thing
     * it is about to write does not have to ask whether it exists first, and a repeat write lands on
     * the same row instead of adding another. The event store's ReplacingMergeTree then collapses
     * the duplicate on its own.
     *
     * <p>Tenant-scoped like the other keys here: two tenants deriving an id from the same text must
     * not collide, both because they live in different schemas and because a collision would be
     * silent.
     *
     * <p>Not a UUID version 7 and not a name-based UUID version 5 — no version or variant bits are
     * set, the value is 128 bits of Blake3 laid down as-is. That is fine for the columns it lands in
     * (ClickHouse {@code UUID} and a KVRocks key are both 128 opaque bits) but it means the value
     * carries no timestamp, so it must not be used anywhere that sorts by id and expects time order.
     */
    public static UUID deterministicUUID(String text, String tenant) {
        ByteBuffer bb = ByteBuffer.wrap(blake3Hash(text + tenant, 16));
        return new UUID(bb.getLong(), bb.getLong());
    }

    // 128-bit is standard for distributed systems (Same as UUID)
    public static BigInteger generate128bitKey(String text, String tenant) {
        // '1' as the signum forces the number to be positive
        return new BigInteger(1, blake3Hash(text + tenant, 16));
    }

    /**
     * {@link #generate128bitKey} reinterpreted as a signed 128-bit integer.
     *
     * <p>The key above is unsigned: 128 random bits read as a positive number, so half of all keys
     * exceed 2^127. ClickHouse stores them in an {@code Int128} column, which is <em>signed</em>, so
     * those land as their negative two's-complement twin — the same bits, a different number. Any
     * query comparing against the unsigned form therefore misses exactly half the rows, and misses
     * them silently: the id looks right, the query is well-formed, and the result is empty.
     *
     * <p>Callers that write or match {@code external_id_hash} want this form. The unsigned one is
     * still correct for stores that hold it as unsigned or as bytes.
     */
    public static BigInteger generate128bitKeySigned(String text, String tenant) {
        BigInteger unsigned = generate128bitKey(text, tenant);
        return unsigned.testBit(127) ? unsigned.subtract(TWO_POW_128) : unsigned;
    }

    private static final BigInteger TWO_POW_128 = BigInteger.ONE.shiftLeft(128);

    // 256-bit is effectively collision-impossible, but takes more storage
    public static BigInteger generate256bitKey(String text, String tenant) {
        return new BigInteger(1, blake3Hash(text + tenant, 32));
    }

    public static long xxHash(@NotNull String text){
        return LongHashFunction.xx3().hashChars(text);
    }
}
