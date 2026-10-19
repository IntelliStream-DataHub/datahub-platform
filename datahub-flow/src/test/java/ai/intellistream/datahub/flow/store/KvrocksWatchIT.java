// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.flow.store;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.TransactionResult;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the run store's transactions rest on: that KVRocks discards a {@code MULTI} when another
 * connection wrote a key watched since, whatever the key's type, and not otherwise.
 */
@Tag("integration")
class KvrocksWatchIT {

    @Test
    void aWatchedKeyWrittenByAnotherConnectionDiscardsTheTransaction() {
        SharedKvrocks.flush();
        var k = SharedKvrocks.kvrocks(0);
        RedisClient client = RedisClient.create();
        var a = client.connect(RedisURI.create(k.getHost(), k.getPort())).sync();
        var b = client.connect(RedisURI.create(k.getHost(), k.getPort())).sync();
        for (String type : new String[]{"hash", "set", "zset", "string"}) {
            String key = "probe:" + type;
            Runnable write = switch (type) {
                case "hash" -> () -> b.hset(key, "f", "1");
                case "set" -> () -> b.sadd(key, "m");
                case "zset" -> () -> b.zadd(key, 1, "m");
                default -> () -> b.set(key, "v");
            };
            // A write by another connection between WATCH and EXEC discards the transaction.
            a.watch(key);
            a.exists(key);
            write.run();
            a.multi();
            a.set("probe:out", "x");
            TransactionResult discarded = a.exec();
            assertThat(discarded.wasDiscarded()).as(type + " conflict").isTrue();
            // And an untouched key does not.
            a.watch(key);
            a.exists(key);
            a.multi();
            a.set("probe:out", "x");
            TransactionResult ok = a.exec();
            assertThat(ok.wasDiscarded()).as(type + " no conflict").isFalse();
            // A delete by another connection counts too, and so does a write that changes nothing.
        }
        a.watch("probe:hash");
        b.del("probe:hash");
        a.multi();
        a.set("probe:out", "y");
        assertThat(a.exec().wasDiscarded()).as("delete").isTrue();
        // An expiry of the watched key counts too.
        a.watch("probe:set");
        b.expire("probe:set", 100);
        a.multi();
        a.set("probe:out", "z");
        assertThat(a.exec().wasDiscarded()).as("expire").isTrue();
        // And a write to a key that is not watched does not.
        a.watch("probe:set");
        b.set("probe:other", "v");
        a.multi();
        a.set("probe:out", "z");
        assertThat(a.exec().wasDiscarded()).as("other key").isFalse();
        client.shutdown();
    }
}
