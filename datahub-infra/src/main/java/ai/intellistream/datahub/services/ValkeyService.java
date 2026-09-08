// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.services;

import ai.intellistream.datahub.api.responses.DataCollection;
import ai.intellistream.datahub.api.responses.DatapointString;
import ai.intellistream.datahub.helpers.utils.IdGenerator;
import ai.intellistream.datahub.config.ValkeyConnectionProvider;
import ai.intellistream.datahub.tenant.TenantContext;
import ai.intellistream.datahub.jpa.domains.NodeEntity;
import ai.intellistream.datahub.jpa.domains.TimeseriesEntity;
import ai.intellistream.datahub.jpa.domains.TimeseriesValueType;
import ai.intellistream.datahub.jpa.dto.*;
import ai.intellistream.datahub.models.forms.RetrieveFilter;
import ai.intellistream.datahub.util.DatapointsResult;
import ai.intellistream.datahub.util.SavedDatapointsStats;
import com.clickhouse.client.api.data_formats.ClickHouseBinaryFormatReader;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import com.fasterxml.jackson.core.JsonProcessingException;
import io.lettuce.core.KeyValue;
import io.lettuce.core.Range;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.SetArgs;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.async.RedisAsyncCommands;
import io.lettuce.core.api.sync.RedisCommands;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;



/**
 * The ValkeyService class is responsible for handling operations related to
 * saving, retrieving, and managing datapoints and their associated metadata.
 * It interacts with various components, such as Redis, JSON processing libraries,
 * and external systems, to perform its operations.
 *
 * <p>Note: This class should be refactored in the future to utilize the
 * ValkeyTimeseries module. Moving to a native time-series module will provide
 * additional benefits such as improved aggregation performance, downsampling,
 * and significantly better data compression compared to current JSON storage.</p>
 *
 * Class Fields:
 * - valkeyConnections: Supplies per-tenant Valkey connections to the current tenant's Valkey instance.
 * - jsonMapper: A utility for converting objects to and from JSON format.
 * - log: A logging utility for recording application events.
 *
 * Public Methods:
 * - saveDatapoints: Saves datapoints for a given node using a ClickHouse binary format reader,
 *                   and tracks statistics related to the saved datapoints.
 * - saveAggregatedDatapoints: Saves aggregated datapoints for a given node and tracks statistics,
 *                             using a retrieve filter to determine the datapoints to save.
 * - fetchDatapointsFromCursor: Retrieves a collection of datapoints from a specified cursor position
 *                              with a maximum number of elements.
 * - getListSize: Retrieves the size of a list associated with the given key.
 * - delete: Deletes a list or other data entity associated with the given key.
 * - fetchLatestDatapoint: Fetches the latest datapoint for a specific external ID.
 * - setLatestDatapoint: Sets the latest datapoint for a specific external ID.
 *
 * Private Methods:
 * - handleLastChunk: Handles the processing of the last chunk of datapoints for a given node,
 *                    and updates aggregates and statistics in Redis.
 * - createJsonObject: Creates a JSON object for the given node, aggregates, and statistics,
 *                     and interacts with Redis for storing data.
 * - createMetadataStr: Constructs a metadata string for the given node and aggregates.
 * - transformTsJsonIntoDataCollection: Transforms a Timeseries JSON object into a generic DataCollection.
 * - transformJsonToDataCollection: Converts a Timeseries JSON object into a DataCollection.
 * - transformJsonToAggregatedDataCollection: Converts a Timeseries JSON object into an aggregated DataCollection.
 * - getDatapoint: Retrieves a datapoint of a specific type from the Timeseries JSON object.
 */
@Component
@Slf4j
@AllArgsConstructor
public class ValkeyService {

    private final ValkeyConnectionProvider valkeyConnections;
    // Create an ObjectMapper instance
    private final JsonMapper jsonMapper;

    // 5 min
    private final int DEFAULT_EXPIRE_TIME = 300;

    public void saveDatapoints(
            TimeseriesEntity node,
            ClickHouseBinaryFormatReader reader,
            SavedDatapointsStats stats
    ) {

        // Redis List Name
        String listName = stats.getId();

        StatefulRedisConnection<String, String> connection = valkeyConnections.connection();
        try {
            // Obtain async commands for pipelining
            RedisAsyncCommands<String, String> client = connection.async();

            long tableType = node.getValueType().getId();

            while(reader.hasNext()){
                reader.next(); // Read the next record from stream and parse it

                ZonedDateTime timestamp = reader.getZonedDateTime("timestamp_g");
                // Create a JSON object
                ObjectNode jsonObject = jsonMapper.createObjectNode();
                jsonObject.put("timestamp", timestamp.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));

                if (tableType == TimeseriesValueType.BIGINT) {
                    jsonObject.put("value", reader.getLong("value") );
                } else if (tableType == TimeseriesValueType.FLOAT || tableType == TimeseriesValueType.FLOAT32) {
                    jsonObject.put("value", reader.getDouble("value") );
                } else if (tableType == TimeseriesValueType.NUMERIC || tableType == TimeseriesValueType.DECIMAL32) {
                    jsonObject.put("value", reader.getBigDecimal("value") );
                } else if (tableType == TimeseriesValueType.TEXT || tableType == TimeseriesValueType.MIXED) {
                    jsonObject.put("value", reader.getString("value") );
                }

                createJsonObject(node, null, stats, jsonObject, client, listName, connection);
            }

            handleLastChunk(node, null, stats, listName, client, connection);
        } catch (Exception e) {
            // Any failure here (including the swallowed Execution/Interrupted/JsonProcessing cases)
            // must complete the future exceptionally, or the request thread blocks on get() forever.
            log.error(e.getMessage(), e);
            stats.getFirstDataBatch().completeExceptionally(e);
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void handleLastChunk(
            TimeseriesEntity node,
            Collection<String> aggregates,
            SavedDatapointsStats stats,
            String listName,
            RedisAsyncCommands<String, String> client,
            StatefulRedisConnection<String, String> connection
    ) throws JsonProcessingException, InterruptedException, ExecutionException {
        // If there was only one batch, there is no reason to insert into ValKey
        if(stats.getTotalBatches() == 0){
            DatapointsResult dpr = new DatapointsResult(0, node, null, aggregates);
            dpr.getDatapoints().addAll(stats.getDatapoints());
            stats.getFirstDataBatch().complete(dpr);
        } else {
            if(!stats.getDatapoints().isEmpty()){
                stats.incrementTotalBatches();
                client.rpush(listName, stats.getDatapoints().toArray(new String[0]));
                connection.flushCommands();
            }
            // Refresh the TTLs AFTER the tail rpush: EXPIRE on a key that doesn't exist yet is a
            // no-op, and when the whole tail fits in this final rpush that is what creates the list.
            String metadataName = listName + "-metadata";
            client.expire(metadataName, DEFAULT_EXPIRE_TIME);
            client.expire(listName, DEFAULT_EXPIRE_TIME);
            Long listSize = client.llen(listName).get();
            log.debug("Elements in Valkey: {}, Total processed datapoints: {}", listSize, stats.getSumSavedDatapoints());

            connection.flushCommands();
        }
    }

    private void createJsonObject(
            TimeseriesEntity node,
            Collection<String> aggregates,
            SavedDatapointsStats stats,
            ObjectNode jsonObject,
            RedisAsyncCommands<String, String> client,
            String listName,
            StatefulRedisConnection<String, String> connection
    ) {
        try {

            // Read-ahead: flush a full chunk only once the NEXT row has arrived (this call proves
            // there is more data), so the first chunk's result carries a cursor to page through the
            // rest — potentially millions/billions of rows streaming into Valkey. If the stream
            // instead ends with a full-but-unflushed buffer, handleLastChunk completes it inline with
            // NO cursor, so a result whose size is an exact multiple of the chunk avoids an empty
            // follow-up request.
            if(stats.getDatapoints().size() == stats.getMaxDatapoints()){
                stats.incrementTotalBatches();
                log.debug("Saved datapoints {}, max datapoints: {}",
                        stats.getSumSavedDatapoints(),
                        stats.getMaxDatapoints());

                // Submit the first batch back to the controller request
                if(stats.getTotalBatches() == 1){
                    // Write the cursor metadata as soon as the cursor is handed back: everything past
                    // this first chunk lands in the Valkey list, and fetchDatapointsFromCursor needs
                    // this metadata to reconstruct the value type when the client pages through it.
                    // Created WITH a TTL so it can never leak, even if the stream fails before
                    // handleLastChunk runs; every later access refreshes it.
                    String metadataName = listName + "-metadata";
                    client.set(metadataName, createMetadataStr(node, aggregates),
                            SetArgs.Builder.ex(DEFAULT_EXPIRE_TIME));

                    DatapointsResult dpr = new DatapointsResult(
                            stats.getTotalBatches(),
                            node,
                            stats.getId(),
                            aggregates
                    );
                    dpr.getDatapoints().addAll(stats.getDatapoints());
                    stats.getFirstDataBatch().complete(dpr);
                } else {
                    // The remaining datapoints should be pushed into ValKey
                    client.rpush(listName, stats.getDatapoints().toArray(new String[0]));
                    // EXPIRE after the rpush (a TTL cannot be set on a key that doesn't exist yet):
                    // the first rpush creates the list with a TTL — so a stream that dies mid-way
                    // can't leak it — and each later rpush refreshes the TTL while producing.
                    client.expire(listName, DEFAULT_EXPIRE_TIME);
                    connection.flushCommands();
                }

                stats.getDatapoints().clear();
                Long listSize = client.llen(listName).get();
                log.debug("Elements in Valkey: {}", listSize);
            }

            String json = jsonMapper.writeValueAsString(jsonObject);
            stats.getDatapoints().add(json);
            stats.incrementSavedDatapoints();

        } catch (JsonProcessingException |
                 InterruptedException | ExecutionException e) {
            throw new RuntimeException(e);
        }
    }

    public void saveAggregatedDatapoints(
            TimeseriesEntity node,
            ClickHouseBinaryFormatReader reader,
            RetrieveFilter f,
            SavedDatapointsStats stats
    ) {

        // Redis List Name
        String listName = stats.getId();

        StatefulRedisConnection<String, String> connection = valkeyConnections.connection();
        try {
            // Obtain async commands for pipelining
            RedisAsyncCommands<String, String> client = connection.async();

            // Labeled loop
            recordLoop:
            while (reader.hasNext()) {
                reader.next(); // Read the next record from stream and parse it

                ZonedDateTime timestamp = reader.getZonedDateTime("timestamp_g");
                // Create a JSON object
                ObjectNode jsonObject = jsonMapper.createObjectNode();
                jsonObject.put("timestamp", timestamp.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));

                for(String aggregate : f.getAggregates()){
                    switch(aggregate.toLowerCase()){
                        case "avg": {
                            if(!reader.hasValue(aggregate)) continue recordLoop;
                            jsonObject.put("average", reader.getDouble(aggregate) );
                        } break;
                        case "min": jsonObject.put("min", reader.getDouble(aggregate) ); break;
                        case "max": jsonObject.put("max", reader.getDouble(aggregate) ); break;
                        case "sum": jsonObject.put("sum", reader.getDouble(aggregate) ); break;
                    }
                }

                createJsonObject(node, f.getAggregates(), stats, jsonObject, client, listName, connection);
            }

            handleLastChunk(node, f.getAggregates(), stats, listName, client, connection);

        } catch (ExecutionException | JsonProcessingException |
                 InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    private String createMetadataStr(TimeseriesEntity node, Collection<String> aggregates) throws JsonProcessingException {
        ObjectNode jsonObject = jsonMapper.createObjectNode();
        jsonObject.put("id", node.getId());
        jsonObject.put("externalId", node.getExternalId());
        jsonObject.put("unit", node.getUnit());
        jsonObject.put("unitExternalId", node.getUnitExternalId());
        jsonObject.put("valueType", node.getValueType().getId());
        if(aggregates != null && !aggregates.isEmpty()){
            ArrayNode jsonArray = jsonMapper.createArrayNode();
            for(String aggregate : aggregates){
                jsonArray.add(aggregate);
            }
            jsonObject.set("aggregates", jsonArray);
        }
        return jsonMapper.writeValueAsString(jsonObject);
    }

    @Getter
    @Setter
    @AllArgsConstructor
    @NoArgsConstructor
    private static class TimeseriesJson {
        private Long id;
        private String externalId;
        private String unit;
        private String unitExternalId;
        private Integer valueType;

        @JsonInclude(JsonInclude.Include.NON_NULL)
        private List<String> aggregates;


    }

    public DataCollection<?> fetchDatapointsFromCursor(String cursor, long maxElements) {
        DataCollection<?> dataCollection = new DataCollection<>();
        String metadataName = cursor + "-metadata";
        StatefulRedisConnection<String, String> connection = valkeyConnections.connection();
        try {
            // Obtain async commands for pipelining
            RedisCommands<String, String> client = connection.sync();

            String timeseriesJsonString = client.get(metadataName);
            if(timeseriesJsonString == null){
                // If no metadata found, it means that there is no more data for this cursor
                return dataCollection;
            }
            // Update expire time for metadata when we have accessed it
            client.expire(metadataName, DEFAULT_EXPIRE_TIME);
            TimeseriesJson ts = jsonMapper.readValue(timeseriesJsonString, TimeseriesJson.class);
            transformTsJsonIntoDataCollection(ts, dataCollection);

            List<String> jsonDatapoints = client.lpop(cursor, maxElements);

            // Update expire time for cursor when we have accessed it, this means that if a user
            // spends a lot of time processing the datapoints, it will to expire to early.
            client.expire(cursor, DEFAULT_EXPIRE_TIME);
            if(ts.getAggregates() != null && !ts.getAggregates().isEmpty()){
                // Transform aggregated datapoints
                dataCollection = transformJsonToAggregatedDataCollection(ts, jsonDatapoints);
            } else {
                // Transform raw datapoints
                dataCollection = transformJsonToDataCollection(ts, jsonDatapoints);
            }

        } catch (JsonProcessingException e) {
            throw new RuntimeException(e.getMessage(), e);
        }
        log.debug("Fetched {} datapoints from cursor {}", dataCollection.getDatapoints().size(), cursor);
        return dataCollection;
    }

    private void transformTsJsonIntoDataCollection(TimeseriesJson tsJson, DataCollection<?> dataCollection) {
        dataCollection.setId(tsJson.getId());
        dataCollection.setExternalId(tsJson.getExternalId());
        dataCollection.setUnit(tsJson.getUnit());
        dataCollection.setUnitExternalId(tsJson.getUnitExternalId());
    }

    // transformJsonToDataCollection is very similar to transformJsonToDataCollection in TimeseriesService
    // Todo: Refactor into one
    private DataCollection<?> transformJsonToDataCollection(TimeseriesJson tsJson, List<String> datapoints) throws JsonProcessingException {
        if(tsJson.getValueType() == TimeseriesValueType.BIGINT){
            DataCollection<DatapointBigIntDTO> dc = getDatapoint(tsJson, DatapointBigIntDTO.class);
            for(String dpString : datapoints){
                DatapointBigIntDTO dp = jsonMapper.readValue(dpString, DatapointBigIntDTO.class);
                dc.getDatapoints().add(dp);
            }
            return dc;
        } else if(tsJson.getValueType() == TimeseriesValueType.FLOAT
                || tsJson.getValueType() == TimeseriesValueType.FLOAT32){
            DataCollection<DatapointFloatDTO> dc = getDatapoint(tsJson, DatapointFloatDTO.class);
            for(String dpString : datapoints){
                DatapointFloatDTO dp = jsonMapper.readValue(dpString, DatapointFloatDTO.class);
                dc.getDatapoints().add(dp);
            }
            return dc;
        } else if(tsJson.getValueType() == TimeseriesValueType.NUMERIC
                || tsJson.getValueType() == TimeseriesValueType.DECIMAL32){
            DataCollection<DatapointNumericDTO> dc = getDatapoint(tsJson, DatapointNumericDTO.class);
            for(String dpString : datapoints){
                DatapointNumericDTO dp = jsonMapper.readValue(dpString, DatapointNumericDTO.class);
                dc.getDatapoints().add(dp);
            }
            return dc;
        } else if(tsJson.getValueType() == TimeseriesValueType.TEXT
                || tsJson.getValueType() == TimeseriesValueType.MIXED){
            DataCollection<DatapointTextDTO> dc = getDatapoint(tsJson, DatapointTextDTO.class);
            for(String dpString : datapoints){
                DatapointTextDTO dp = jsonMapper.readValue(dpString, DatapointTextDTO.class);
                dc.getDatapoints().add(dp);
            }
            return dc;
        }
        throw new RuntimeException("Error with value type for processing json");
    }

    private DataCollection<?> transformJsonToAggregatedDataCollection(TimeseriesJson tsJson, List<String> datapoints) throws JsonProcessingException {
        // No value-type gate: aggregates (avg/min/max/sum) are valid for every numeric value
        // type, and saveAggregatedDatapoints serializes the cursor rows as type-agnostic
        // DatapointAggsDTO JSON regardless of the timeseries type. Mirrors the hasAggregates()
        // branch of TimeseriesService.transformJsonToDataCollection (the first-batch path).
        DataCollection<DatapointAggsDTO> dc = getDatapoint(tsJson, DatapointAggsDTO.class);
        for(String dpString : datapoints){
            DatapointAggsDTO dp = jsonMapper.readValue(dpString, DatapointAggsDTO.class);
            dc.getDatapoints().add(dp);
        }
        return dc;
    }

    private <T> DataCollection<T> getDatapoint(TimeseriesJson tsJson, Class<T> type) {
        DataCollection<T> dc = new DataCollection<>();
        dc.setId(tsJson.getId());
        dc.setExternalId(tsJson.getExternalId());
        dc.setUnit(tsJson.getUnit());
        dc.setUnitExternalId(tsJson.getUnitExternalId());
        return dc;
    }

    public long getListSize(String key) {
        RedisCommands<String, String> client = valkeyConnections.connection().sync();
        return client.llen(key);
    }

    public void delete(String key) {
        StatefulRedisConnection<String, String> connection = valkeyConnections.connection();
        RedisCommands<String, String> client = connection.sync();
        client.del(key + TenantContext.getTenantId());
        connection.flushCommands();
    }

    public void put(Map<String, String> values) {
        if(values == null || values.isEmpty()){
            return;
        }

        StatefulRedisConnection<String, String> connection = valkeyConnections.connection();
        RedisCommands<String, String> client = connection.sync();
        client.mset(values);
        connection.flushCommands();
    }

    public DatapointString fetchLatestDatapoint(String externalId) throws JsonProcessingException {
        RedisCommands<String, String> client = valkeyConnections.connection().sync();

        String dpString = client.get(latestDatapointKey(externalId));
        if(dpString == null){
            return null;
        }
        return jsonMapper.readValue(dpString, DatapointString.class);
    }

    public void setLatestDatapoint(String externalId, DatapointString dp) throws JsonProcessingException {
        StatefulRedisConnection<String, String> connection = valkeyConnections.connection();
        RedisCommands<String, String> client = connection.sync();
        String json = jsonMapper.writeValueAsString(dp);
        client.set(latestDatapointKey(externalId), json);
        connection.flushCommands();
    }

    /**
     * Evicts the cached latest datapoint for {@code externalId}. Called when a timeseries is deleted:
     * the entry has no TTL, and the key is derived from the externalId rather than the (never reused)
     * internal id, so a timeseries recreated under the same externalId would inherit the dead value.
     * Note this is {@link #latestDatapointKey}-scoped and deliberately not {@link #delete}, which
     * applies a different tenant-suffix key convention.
     */
    public void deleteLatestDatapoint(String externalId) {
        StatefulRedisConnection<String, String> connection = valkeyConnections.connection();
        connection.sync().del(latestDatapointKey(externalId));
        connection.flushCommands();
    }

    /** Read a plain string value from the current tenant's Valkey (null if absent). */
    public String getString(String key) {
        return valkeyConnections.connection().sync().get(key);
    }

    /** Write a plain string value with an expiry (seconds) to the current tenant's Valkey. */
    public void setString(String key, String value, long ttlSeconds) {
        StatefulRedisConnection<String, String> connection = valkeyConnections.connection();
        connection.sync().setex(key, ttlSeconds, value);
        connection.flushCommands();
    }

    /**
     * Atomically add {@code delta} to the counter at {@code key} (INCRBY) in the current tenant's
     * Valkey, creating it at 0 first if absent, and return the new value. Safe under concurrent
     * callers (e.g. multiple API instances incrementing the same key) — INCRBY is atomic
     * server-side, no read-modify-write race.
     */
    public long increment(String key, long delta) {
        return valkeyConnections.connection().sync().incrby(key, delta);
    }

    /**
     * INCRBY plus an expiry set only when this call created the key, and the new value returned.
     *
     * <p>The expiry has to be applied in the same command as the increment. Sent as two, a crash in
     * between leaves a counter that never expires, and for a windowed counter (a rate-limit bucket, a
     * per-day quota) an immortal key means the window never rolls: the tenant stays refused until
     * someone deletes it by hand.
     *
     * <p>One EVAL is one auto-flushed command, so this stays inside what the shared per-tenant
     * connection allows — unlike MULTI/EXEC, which would need a connection of its own.
     */
    public long incrementAndExpireIfNew(String key, long delta, long ttlSeconds) {
        Object result = valkeyConnections.connection().sync().eval(
                INCR_AND_EXPIRE_IF_NEW,
                ScriptOutputType.INTEGER,
                new String[]{key},
                String.valueOf(delta), String.valueOf(ttlSeconds));
        return result instanceof Number n ? n.longValue() : 0L;
    }

    /**
     * Sets the TTL only when the increment created the key: comparing the result against the delta
     * is what distinguishes "first write in this window" from "another instance got here first", and
     * it avoids pushing the expiry out on every hit, which would keep a busy key alive forever.
     */
    private static final String INCR_AND_EXPIRE_IF_NEW = """
            local total = redis.call('INCRBY', KEYS[1], ARGV[1])
            if total == tonumber(ARGV[1]) then
              redis.call('EXPIRE', KEYS[1], ARGV[2])
            end
            return total""";

    /**
     * Record {@code member} as alive right now, in the sorted set at {@code key}.
     *
     * <p>A heartbeat-scored set rather than a plain counter, so entries left behind by an instance
     * that died mid-connection age out by themselves instead of occupying a budget forever. The key
     * carries an expiry a few multiples of the staleness window, so a set nobody refreshes
     * disappears rather than lingering empty.
     */
    public void touchMember(String key, String member, long staleAfterSeconds) {
        RedisCommands<String, String> client = valkeyConnections.connection().sync();
        client.zadd(key, (double) Instant.now().getEpochSecond(), member);
        client.expire(key, staleAfterSeconds * 4);
    }

    /** Remove a member from the sorted set at {@code key}. */
    public void removeMember(String key, String member) {
        valkeyConnections.connection().sync().zrem(key, member);
    }

    /**
     * How many members of {@code key} have been seen within {@code staleAfterSeconds}, dropping the
     * ones that have not. The prune runs here rather than on a timer because this is the only place
     * that has to be right, and it keeps the set from growing without bound.
     */
    public long countLiveMembers(String key, long staleAfterSeconds) {
        RedisCommands<String, String> client = valkeyConnections.connection().sync();
        long cutoff = Instant.now().getEpochSecond() - staleAfterSeconds;
        client.zremrangebyscore(key, Range.create(Double.NEGATIVE_INFINITY, (double) cutoff));
        return client.zcard(key);
    }

    /**
     * SET-if-not-exists (SETNX) on the current tenant's Valkey: writes {@code value} only if
     * {@code key} is absent, atomically. Returns true if this call created the key. Safe under
     * concurrent callers (e.g. multiple API instances racing to seed the same counter) — whichever
     * call wins is the only one that writes; the rest are no-ops.
     */
    public boolean setIfAbsent(String key, String value) {
        return Boolean.TRUE.equals(valkeyConnections.connection().sync().setnx(key, value));
    }

    /**
     * Read several keys from the current tenant's Valkey in a single round trip (MGET). The returned
     * map contains only the keys that were present, so a caller can tell hits from misses and compute
     * just the misses.
     */
    public Map<String, String> multiGet(List<String> keys) {
        if (keys == null || keys.isEmpty()) return Map.of();
        Map<String, String> out = new HashMap<>();
        for (KeyValue<String, String> kv : valkeyConnections.connection().sync().mget(keys.toArray(new String[0]))) {
            if (kv.hasValue()) out.put(kv.getKey(), kv.getValue());
        }
        return out;
    }

    /**
     * Valkey is shared across tenants and externalIds are user-chosen, so the key must include
     * the tenant or equal externalIds in different tenants would read/overwrite each other's
     * cached latest datapoint. Same hashing strategy as KVRocksService.
     */
    private static String latestDatapointKey(String externalId) {
        return "latest-" + IdGenerator.generate128bitKey(externalId, TenantContext.getTenantId()).toString(16);
    }
}
