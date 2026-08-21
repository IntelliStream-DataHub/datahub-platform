// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.services;

import ai.intellistream.datahub.config.KVRocksConnectionProvider;
import ai.intellistream.datahub.helpers.utils.IdGenerator;
import ai.intellistream.datahub.jpa.dto.UUIDAndBigIntHash;
import ai.intellistream.datahub.models.EventModel;
import ai.intellistream.datahub.tenant.TenantContext;
import io.lettuce.core.RedisException;
import io.lettuce.core.api.StatefulRedisConnection;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.util.*;

/**
 * Service for managing event mappings in KVRocks (Redis-compatible storage).
 *
 * Multi-tenancy:
 * Each tenant has its own KVRocks instance; connections are routed per tenant by
 * {@link KVRocksConnectionProvider} (host/port from the tenant's Vault config). Keys are also
 * derived from a 128-bit BLAKE3 hash of the combination of 'externalId' and 'tenantId', which keeps
 * them collision-resistant and uniformly distributed within an instance.
 *
 * Safety of 128-bit keys:
 * 1. Distributed Collision Resistance: A 128-bit space ($2^{128}$) is vast enough that the
 *    probability of two distinct (externalId + tenantId) combinations resulting in the same hash
 *    is statistically zero for any practical data scale (requiring approximately $2^{64}$ keys
 *    before a 50% collision chance).
 * 2. Cryptographic Uniformity: BLAKE3 ensures that even minor differences in input
 *    (like a different tenantId) produce wildly different hashes, preventing clusters or hotspots.
 * 3. Space Efficiency: 128-bit keys (16 bytes) are optimized for memory and indexing speed in
 *    KVRocks compared to larger 256-bit hashes.
 */
@Slf4j
@Component
public class KVRocksService {

    private final KVRocksConnectionProvider kvRocksConnections;

    public KVRocksService(KVRocksConnectionProvider kvRocksConnections) {
        this.kvRocksConnections = kvRocksConnections;
    }

    public void saveEvents(List<EventModel> events) {
        try (StatefulRedisConnection<byte[], byte[]> connection = kvRocksConnections.openConnection()) {
            var client = connection.sync();
            Map<BigInteger, List<byte[]>> externalIdToUuidsMap = new HashMap<>();
            for(EventModel event : events){
                UUID id = UUID.fromString(event.getId());
                ByteBuffer bb = ByteBuffer.wrap(new byte[16]);
                bb.putLong(id.getMostSignificantBits());
                bb.putLong(id.getLeastSignificantBits());
                byte[] uuidBytes = bb.array();

                var extId = event.getExternalId();
                var tenant = TenantContext.getTenantId();
                BigInteger externalIdHash = IdGenerator.generate128bitKey(extId, tenant);

                // Add the UUID byte array to the list associated with the externalId hash
                externalIdToUuidsMap
                        .computeIfAbsent(externalIdHash, k -> new ArrayList<>())
                        .add(uuidBytes);
            }
            // For each externalId hash, add all associated UUIDs to a Redis set
            for(Map.Entry<BigInteger, List<byte[]>> entry : externalIdToUuidsMap.entrySet()){
                byte[] externalIdKey = entry.getKey().toByteArray();
                byte[][] uuidMembers = entry.getValue().toArray(new byte[0][]);
                client.sadd(externalIdKey, uuidMembers);
            }
        } catch (RedisException e) {
            log.debug(e.getMessage(), e);
        }
    }

    public void updateKeys(Map<byte[], byte[]> map, String tenantId) {
        try (StatefulRedisConnection<byte[], byte[]> connection = kvRocksConnections.openConnection(tenantId)) {
            var client = connection.sync();
            client.multi();
            for(Map.Entry<byte[], byte[]> entry : map.entrySet()){
                client.rename(entry.getKey(), entry.getValue());
            }
            client.exec();
        } catch (RedisException e) {
            log.debug(e.getMessage(), e);
        }
    }

    public List<UUID> findEventIdsByExternalIdCollection(Collection<String> externalIdList) {
        try (StatefulRedisConnection<byte[], byte[]> con = kvRocksConnections.openConnection()) {
            var client = con.sync();
            List<UUID> uuidList = new ArrayList<>();

            for (String extId : externalIdList) {
                var tId = TenantContext.getTenantId();
                BigInteger idHash = IdGenerator.generate128bitKey(extId, tId);

                // Retrieve all members from the set
                Set<byte[]> uuidBytesSet = client.smembers(idHash.toByteArray());

                if (uuidBytesSet != null) {
                    for (byte[] uuidBytes : uuidBytesSet) {
                        ByteBuffer byteBuffer = ByteBuffer.wrap(uuidBytes);
                        uuidList.add(new UUID(byteBuffer.getLong(), byteBuffer.getLong()));
                    }
                }
            }
            return uuidList;
        }
    }

    public Map<UUID, BigInteger> findEventIdsByExternalIdCollectionAsMap(
            Collection<String> externalIdList
    ) {
        try (StatefulRedisConnection<byte[], byte[]> connection = kvRocksConnections.openConnection()) {
            var client = connection.sync();
            Map<UUID, BigInteger> map = new HashMap<>();

            for (String externalId : externalIdList) {
                var tId = TenantContext.getTenantId();
                BigInteger externalIdHash = IdGenerator.generate128bitKey(externalId, tId);
                // Retrieve all members from the set
                Set<byte[]> uuidBytesSet = client.smembers(externalIdHash.toByteArray());

                if (uuidBytesSet != null) {
                    for (byte[] uuidBytes : uuidBytesSet) {
                        ByteBuffer bbuf = ByteBuffer.wrap(uuidBytes);
                        map.put(new UUID(bbuf.getLong(), bbuf.getLong()), externalIdHash);
                    }
                }
            }
            return map;
        }
    }

    public byte[][] getBinaryKeys(Collection<String> texts){
        List<byte[]> hashes = texts.stream().map( it -> {
            BigInteger id = IdGenerator.generate128bitKey(it, TenantContext.getTenantId());
            return id.toByteArray();
        } ).toList();

        byte[][] keys = hashes.toArray(new byte[hashes.size()][]);
        return keys;
    }

    public static byte[][] extractKeysToByteArray(Map<byte[], byte[]> map) {
        // Create an array to hold the keys
        byte[][] keys = new byte[map.size()][];

        int i = 0;
        for (byte[] key : map.keySet()) {
            keys[i++] = key;  // Add key to the array
        }

        return keys;
    }

    public void deleteKeys(Collection<byte[]> externalIdSet, String tenantId) {
        if(!externalIdSet.isEmpty()){
            try (StatefulRedisConnection<byte[], byte[]> connection = kvRocksConnections.openConnection(tenantId)) {
                var client = connection.sync();
                byte[][] keysArray = externalIdSet.toArray(new byte[0][]);
                client.del(keysArray);
            } catch (RedisException e) {
                log.debug(e.getMessage(), e);
            }
        }
    }

    public void deleteKeys(List<UUIDAndBigIntHash> idList, String tenantId) {
        if(!idList.isEmpty()){
            try (StatefulRedisConnection<byte[], byte[]> connection = kvRocksConnections.openConnection(tenantId)) {
                var client = connection.sync();
                List<byte[]> keys = new ArrayList<>();
                for(UUIDAndBigIntHash it : idList){
                    keys.add(it.getExternalIdHash().toByteArray());
                    client.srem(it.getExternalIdHash().toByteArray(), convertUuid(it.getId()));
                }
            } catch (RedisException e) {
                log.error(e.getMessage(), e);
            }
        }
    }

    public byte[] convertUuid(UUID id){
        ByteBuffer bb = ByteBuffer.wrap(new byte[16]);
        bb.putLong(id.getMostSignificantBits());
        bb.putLong(id.getLeastSignificantBits());
        return bb.array();
    }
}
