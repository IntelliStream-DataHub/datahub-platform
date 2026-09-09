// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.clickhouse;

import ai.intellistream.datahub.api.responses.DataCollectionBin;
import ai.intellistream.datahub.api.responses.DatapointBin;
import ai.intellistream.datahub.api.responses.DatapointString;
import ai.intellistream.datahub.helpers.datetime.DateTimeHandler;
import ai.intellistream.datahub.helpers.utils.IdGenerator;
import ai.intellistream.datahub.jpa.domains.NodeEntity;
import ai.intellistream.datahub.jpa.domains.TimeseriesEntity;
import ai.intellistream.datahub.jpa.domains.TimeseriesValueType;
import ai.intellistream.datahub.models.forms.RetrieveFilter;
import ai.intellistream.datahub.responses.datapoints.enums.Aggregates;
import ai.intellistream.datahub.services.ValkeyService;
import ai.intellistream.datahub.tenant.TenantConfigService;
import ai.intellistream.datahub.util.DatapointsResult;
import ai.intellistream.datahub.util.SavedDatapointsStats;
import com.clickhouse.client.api.Client;
import com.clickhouse.client.api.insert.InsertResponse;
import com.clickhouse.client.api.query.GenericRecord;
import com.clickhouse.client.api.query.QueryResponse;
import com.clickhouse.client.api.query.QuerySettings;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.*;
import java.math.BigDecimal;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static ai.intellistream.datahub.responses.datapoints.Granularity.createCHGranularity;

@Service
@Slf4j
public class ClickHouseDatapointService extends ClickHouseService{

    public ClickHouseDatapointService(TenantConfigService tenantConfigService, ValkeyService valkeyService, ClickHouseClientPool clickHouseClientPool) {
        super(tenantConfigService, valkeyService, clickHouseClientPool);
    }

    /**
     * Binary ingest path: the producer already encoded each value to ClickHouse RowBinary bytes
     * ({@code DatapointValueCodec}) and parsed the timestamp to epoch millis, so the consumer streams
     * the bytes straight through — no per-value parsing on the hot path.
     */
    public void insertBinaryDatapoints(Collection<DataCollectionBin> items, String tenantId) {
        Map<String, List<DataCollectionBin>> byTable = new HashMap<>();
        for (DataCollectionBin dc : items) {
            if (dc.getDatapoints() == null || dc.getDatapoints().isEmpty()) continue;
            String tn = "datapoints_" + TimeseriesValueType.getTableType(dc.getValueType());
            byTable.computeIfAbsent(tn, k -> new ArrayList<>()).add(dc);
        }

        Client client = getClickhouseClient(tenantId);
        try {
            for (var entry : byTable.entrySet()) {
                String tn = entry.getKey();
                PipedOutputStream rawPipeOut = new PipedOutputStream();
                PipedInputStream pIn = new PipedInputStream(rawPipeOut, BUFFER_SIZE);

                CompletableFuture<Void> writer = CompletableFuture
                        .runAsync(() -> writeBinaryRows(rawPipeOut, entry.getValue()))
                        .exceptionally(ex -> {
                            log.error("Background data writer failed for table {}", tn, ex);
                            closeQuietly(rawPipeOut);
                            return null;
                        });
                try (InsertResponse r = client.insert(tn, pIn, ROWBIN, getSettings()).get(30, TimeUnit.SECONDS)) {
                    log.debug("Rows Written! Server Time: {}", r.getServerTime());
                } catch (Exception e) {
                    log.error("Failed to insert data into ClickHouse table: {}", tn, e);
                    throw new RuntimeException("ClickHouse insert failed", e);
                } finally {
                    closeQuietly(rawPipeOut);
                    writer.join();
                }
            }
        } catch (IOException e) {
            throw new RuntimeException("Error writing data to ClickHouse pipe", e);
        }
    }

    // One RowBinary row per datapoint: timeseries_id (Int64) + timestamp (DateTime64(3), epoch
    // millis) + the pre-encoded value bytes. The value bytes already carry whatever columns the type
    // needs (e.g. MIXED's two nullable columns), so this is value-type agnostic.
    private void writeBinaryRows(PipedOutputStream rawPipeOut, List<DataCollectionBin> collections) {
        try (DataOutputStream dos = new DataOutputStream(new BufferedOutputStream(rawPipeOut))) {
            for (DataCollectionBin dc : collections) {
                for (DatapointBin dp : dc.getDatapoints()) {
                    dos.writeLong(Long.reverseBytes(dc.getId()));
                    dos.writeLong(Long.reverseBytes(dp.getTimestamp()));
                    dos.write(dp.getValue());
                }
            }
        } catch (IOException e) {
            throw new RuntimeException("Error writing data to ClickHouse pipe", e);
        }
    }

    /** One item's DELETE statement plus its bind parameters. Package-private so it can be unit-tested. */
    record DeleteQuery(String sql, Map<String, Object> params) {}

    /**
     * Builds the DELETE for one item. Both bounds are optional: {@code inclusiveBegin} null means
     * "no lower bound" and {@code exclusiveEnd} null means "no upper bound", so an item with neither
     * purges every data-point of that timeseries. That fully-open form is what the timeseries delete
     * emits when the definition itself goes away (see {@code TimeseriesService.deleteTimeseries});
     * the {@code /timeseries/data/delete} endpoint always supplies at least a begin.
     */
    static DeleteQuery buildDeleteQuery(DataCollectionBin dc) {
        // Tolerant of both timestamp forms the API accepts (ISO-8601 or epoch millis). The api
        // normalises the bounds to ISO before publishing, so in practice only the first branch is
        // taken; parsing both here keeps messages from older producers (and the SDK) applicable
        // rather than dead-lettering them on a form the endpoint documents as valid.
        ZonedDateTime startTime = dc.getInclusiveBegin() != null
                ? DateTimeHandler.fromEpochUTCTimeAsZonedDateTime(dc.getInclusiveBegin()) : null;
        ZonedDateTime endTime = dc.getExclusiveEnd() != null
                ? DateTimeHandler.fromEpochUTCTimeAsZonedDateTime(dc.getExclusiveEnd()) : null;

        String tableName = "datapoints_" + TimeseriesValueType.getTableType(dc.getValueType());
        String sql = "DELETE FROM %s WHERE timeseries_id = {timeseriesId:Int64}".formatted(tableName);

        Map<String, Object> queryParams = new HashMap<>();
        queryParams.put("timeseriesId", dc.getId());
        if (startTime != null) {
            sql += " AND timestamp >= {startTime:DateTime64(3)}";
            queryParams.put("startTime", toChDateTime(startTime));
        }
        if (endTime != null) {
            sql += " AND timestamp < {endTime:DateTime64(3)}";
            queryParams.put("endTime", toChDateTime(endTime));
        }
        return new DeleteQuery(sql, queryParams);
    }

    /** Binary delete path over {@link DataCollectionBin}. See {@link #buildDeleteQuery} for the window rules. */
    public void deleteBinaryDatapoints(Collection<DataCollectionBin> items, String tenantId) {
        Client client = getClickhouseClient(tenantId);
        for (DataCollectionBin dc : items) {
            DeleteQuery q = buildDeleteQuery(dc);
            String sql = q.sql();
            Map<String, Object> queryParams = q.params();

            try (QueryResponse response = client.query(sql, queryParams, new QuerySettings()).get(30, TimeUnit.SECONDS)) {
                log.debug("Delete Server Time: {}, QueryId: {}", response.getServerTime(), response.getQueryId());
            } catch (Exception ex) {
                throw new RuntimeException(ex);
            }
        }
    }

    public void optimizeTable(String tableName) {
        Client client = getClickhouseClient();
        try {
            String sql = "OPTIMIZE TABLE %s".formatted(tableName);
            client.query(sql).get(5, TimeUnit.MINUTES);
        } catch (InterruptedException | ExecutionException | TimeoutException e) {
            throw new RuntimeException(e);
        }
    }

    public String handleAggregates(RetrieveFilter f) {
        List<String> aggregates = new ArrayList<>();
        f.getAggregates().forEach( aText -> {
            Aggregates a = Aggregates.findByDescription(aText);
            if(a != null){
                switch (a) {
                    case SUM -> aggregates.add("sumIf(contrib_value, contrib_kind = 'sample') as sum ");
                    case AVG -> aggregates.add("avgWeighted(contrib_value, contrib_weight) AS avg ");
                    case MIN -> aggregates.add("min(contrib_value) as min ");
                    case MAX -> aggregates.add("max(contrib_value) as max ");
                }
            }
        });
        if(aggregates.isEmpty()) return null;
        return String.join(",", aggregates);
    }

    public DatapointString findLatestDatapointInClickhouse(TimeseriesEntity ts){
        var dp = new DatapointString();

        Client client = getClickhouseClient();
        try {
            String valueType = TimeseriesValueType.getTableType( ts.getValueType());
            String tableName = "datapoints_%s".formatted(valueType);
            String valueExpr = TimeseriesValueType.latestValueSql(ts.getValueType().getId());
            String query = ("SELECT timestamp, " + valueExpr + " as value from %s " +
                    "WHERE timeseries_id = {timeseriesId:Int64} ORDER BY timestamp DESC LIMIT 1").formatted(tableName);
            log.debug("ClickHouse query: " + query);

            Map<String, Object> queryParams = new HashMap<>();
            queryParams.put("timeseriesId", ts.getId());

            Collection<GenericRecord> records = client.queryAll(query, queryParams);
            for (GenericRecord record : records) {
                // --- reading DateTime64(3) ---
                // The client automatically maps DateTime64 to LocalDateTime.
                // Since your table DDL didn't specify a timezone, ClickHouse treats it as UTC-ish.
                ZonedDateTime zdt = record.getZonedDateTime("timestamp"); // or record.getLocalDateTime(0)

                // --- reading Value ---
                String val = record.getString("value"); // or record.getString(1)

                // Populate your object
                dp.setTimestamp(zdt.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
                dp.setValue(val);
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        return dp;
    }

    public void prepareDatapointsFetching(
            RetrieveFilter f,
            long maxDatapoints,
            CompletableFuture<DatapointsResult> taskFuture,
            TimeseriesEntity node,
            String tenantId
    ) {
        {
            Client client = getClickhouseClient(tenantId);
            long localMaxDatapoints = maxDatapoints;
            if(f.getLimit() != 0 && f.getLimit() < localMaxDatapoints){
                localMaxDatapoints = f.getLimit();
            }

            SavedDatapointsStats savedDatapointsStats = new SavedDatapointsStats( localMaxDatapoints, taskFuture);

            String valueType = TimeseriesValueType.getTableType( node.getValueType());
            String tableName = "datapoints_%s".formatted(valueType);

            String aggregates = handleAggregates(f);
            try{
                if(aggregates == null){
                    fetchRawClickHouseDatapoints(client, node, f, tableName, savedDatapointsStats);
                } else {
                    fetchAggregates(client, node, f, aggregates, tableName, savedDatapointsStats);
                }
            } catch (Exception e){
                // Complete the future exceptionally — otherwise the waiting request thread
                // (TimeseriesService.retrieveDatapointsFromCH) blocks on future.get() forever.
                log.error(e.getMessage(), e);
                taskFuture.completeExceptionally(e);
            }

        }
    }

    /**
     * Fetches aggregated datapoints from the ClickHouse database based on the provided parameters.
     *
     * @param node The node entity that contains information about the data source.
     * @param f The filter used to retrieve specific data based on granularity and
     *          time constraints.
     * @param aggregates A string representing the aggregation functions
     *                   to be applied (e.g., SUM, AVG).
     * @param tableName The name of the table from which data is fetched.
     * @param savedDatapointsStats An object to store statistics about the saved
     *                             aggregated datapoints.
     * @throws ExecutionException If an error occurs during query execution.
     * @throws InterruptedException If the thread executing the query is interrupted.
     */
    private void fetchAggregates(
            Client client,
            TimeseriesEntity node,
            RetrieveFilter f,
            String aggregates,
            String tableName,
            SavedDatapointsStats savedDatapointsStats
    ) {
        boolean hasGroupBy = true;
        String g = createCHGranularity(f.getGranularity());

        ClickHouseFilterData filterData = createFilters(f, hasGroupBy);
        int valueTypeId = node.getValueType().getId();
        String valueColumn = TimeseriesValueType.aggregateValueSql(valueTypeId);
        // MIXED: drop text rows (value_numeric IS NULL) from the window so duration weighting
        // measures the gap to the next NUMERIC point, not the next text status.
        String aggFilter = filterData.getFilterQuery();
        if (valueTypeId == TimeseriesValueType.MIXED) {
            aggFilter = (aggFilter == null || aggFilter.isBlank())
                    ? "WHERE value_numeric IS NOT NULL"
                    : aggFilter + " AND value_numeric IS NOT NULL";
        }
        String query = buildAggregateQuery(aggregates, valueColumn, g, tableName, aggFilter, f.getLimit() > 0);
        filterData.setQuery(query);

        try (QueryResponse response = createRequest(client, node.getId(), filterData)) {
            valkeyService.saveAggregatedDatapoints(node, client.newBinaryFormatReader(response), f, savedDatapointsStats);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

    }

    /**
     * Builds the bucketed zero-order-hold (ZOH) time-weighted aggregate query. Each reading is held
     * constant until the next reading, so a bucket's {@code avg} is weighted by how long each value was
     * actually in effect <em>within that bucket</em>.
     *
     * <p>Rather than weighting a datapoint by the whole gap to the next point and charging it entirely to
     * the bucket it starts in (which lets a value held across a boundary distort both buckets), the query
     * emits per-bucket <b>contributions</b> and explodes them with {@code ARRAY JOIN}:
     * <ul>
     *   <li><b>Clip.</b> A sample's weight is {@code min(next_timestamp, bucket_end) - timestamp}. The
     *       last sample of the series (no next point, so {@code leadInFrame} returns the epoch default that
     *       is {@code < timestamp}) is held to {@code bucket_end} instead — a known finite span inside its
     *       own bucket — so the final bucket stays exact. Every weight is therefore {@code >= 0} and no
     *       row needs to be dropped (the old {@code QUALIFY isNotNull(duration_seconds)} is gone).
     *   <li><b>Carry-in.</b> The first sample in a bucket also emits a synthetic contribution for the
     *       value held into the bucket from the previous sample: value = previous sample's value, weight =
     *       {@code first_timestamp - bucket_start}. This is correct even across several empty buckets (the
     *       carried value is simply the previous sample's, regardless of distance). The very first sample
     *       of the window has no previous sample and gets no carry-in.
     * </ul>
     * Buckets with no sample of their own emit nothing (a carry-in is only ever produced by a real
     * sample), so gaps are not back-filled and result cardinality matches the pre-fix behaviour.
     *
     * <p>{@code avg = avgWeighted(contrib_value, contrib_weight)} runs over sample <em>and</em> carry-in
     * contributions; {@code min}/{@code max} run over the <em>same</em> set so a carried-in value outside
     * the range of the in-bucket samples still brackets the average (keeping {@code avg} inside
     * {@code [min, max]}); {@code sum} counts only {@code kind = 'sample'} rows so carry-in is never
     * double-counted. Weights are {@code Float64} seconds derived from {@code toUnixTimestamp64Milli} —
     * {@code avgWeighted} mis-accumulates with an integer weight (it can exceed the bucket max even with
     * equal weights), and the DateTime64(3) millis keep sub-second spacing.
     *
     * <p><b>No outer filter on window-derived columns.</b> All window functions, the clip, and the
     * carry-in decision live in the innermost {@code SELECT}; the outer query only groups/aggregates/orders
     * the materialised {@code ARRAY JOIN} output. A {@code WHERE} on a {@code leadInFrame(...) OVER (...)}
     * -derived column one level up can drive ClickHouse's plan optimizer into
     * {@code query_plan_max_optimizations_to_apply} (error 572, TOO_MANY_QUERY_PLAN_OPTIMIZATIONS) even on
     * small tables; doing the last-sample handling by conditional weight rather than a post-window filter
     * avoids that entirely.
     *
     * <p>{@code toStartOfInterval} returns a {@code Date} for week/month/year intervals, which the binary
     * reader's {@code getZonedDateTime("timestamp_g")} rejects, so the bucket start is wrapped in
     * {@code toDateTime} for output; {@code bucket_end = toStartOfInterval(...) + INTERVAL n unit} is
     * calendar-aware and lands exactly on the next bucket start for both fixed and calendar units.
     */
    static String buildAggregateQuery(String aggregates, String valueColumn, String granularity,
                                      String tableName, String aggFilter, boolean hasLimit) {
        String limitFilter = hasLimit ? "LIMIT {limit:Int64}" : "";
        // <G> (granularity) recurs, so fill it (and the rest) by literal token replacement rather than
        // positional format args. None of the substituted values contain another token, so order is safe.
        String template = """
            SELECT time_bucket AS timestamp_g, <AGGREGATES>
            FROM (
                SELECT
                    toDateTime(bucket_start, 'UTC') AS time_bucket,
                    c.1 AS contrib_value,
                    c.2 AS contrib_weight,
                    c.3 AS contrib_kind
                FROM (
                    SELECT
                        toStartOfInterval(timestamp, INTERVAL <G>, 'UTC') AS bucket_start,
                        toDateTime64(toStartOfInterval(timestamp, INTERVAL <G>, 'UTC') + INTERVAL <G>, 3, 'UTC') AS bucket_end,
                        leadInFrame(timestamp, 1) OVER w AS next_timestamp,
                        lagInFrame(value, 1) OVER w AS prev_value,
                        lagInFrame(timestamp, 1, toDateTime64(0, 3, 'UTC')) OVER w AS prev_ts,
                        arrayConcat(
                            [(
                                toFloat64(value),
                                toFloat64(toUnixTimestamp64Milli(
                                    if(next_timestamp < timestamp, bucket_end, least(next_timestamp, bucket_end))
                                ) - toUnixTimestamp64Milli(timestamp)) / 1000.0,
                                'sample'
                            )],
                            if(prev_ts = toDateTime64(0, 3, 'UTC')
                                   OR toStartOfInterval(prev_ts, INTERVAL <G>, 'UTC') = bucket_start,
                               CAST([] AS Array(Tuple(Float64, Float64, String))),
                               [(
                                   toFloat64(prev_value),
                                   toFloat64(toUnixTimestamp64Milli(timestamp)
                                       - toUnixTimestamp64Milli(toDateTime64(bucket_start, 3, 'UTC'))) / 1000.0,
                                   'carryin'
                               )]
                            )
                        ) AS contribs
                    FROM (SELECT <VALUE_SELECT>, timestamp, timeseries_id FROM <TABLE> <AGG_FILTER>)
                    WINDOW w AS (PARTITION BY timeseries_id ORDER BY timestamp ASC ROWS BETWEEN UNBOUNDED PRECEDING AND UNBOUNDED FOLLOWING)
                )
                ARRAY JOIN contribs AS c
            )
            GROUP BY timestamp_g ORDER BY timestamp_g <LIMIT>
            """;
        return template
                .replace("<G>", granularity)
                .replace("<VALUE_SELECT>", valueColumn)
                .replace("<TABLE>", tableName)
                .replace("<AGG_FILTER>", aggFilter == null ? "" : aggFilter)
                .replace("<AGGREGATES>", aggregates)
                .replace("<LIMIT>", limitFilter);
    }

    private void fetchRawClickHouseDatapoints(
            Client client,
            TimeseriesEntity node,
            RetrieveFilter f,
            String tableName,
            SavedDatapointsStats savedDatapointsStats) {

        boolean hasGroupBy = false;
        var filterData = createFilters(f, hasGroupBy);

        String limitFilter = "";
        if(f.getLimit() > 0){
            limitFilter = "LIMIT {limit:Int64}";
        }
        String valueExpr = TimeseriesValueType.rawValueSql(node.getValueType().getId());
        // Order by timestamp so callers get time-ordered datapoints, like the aggregated path.
        // Without this ClickHouse returns rows in storage/merge order, not by timestamp.
        String query = "select timestamp as timestamp_g, %s from %s %s ORDER BY timestamp ASC %s".formatted(valueExpr, tableName, filterData.getFilterQuery(), limitFilter);

        filterData.setQuery(query);

        try (QueryResponse response = createRequest(client, node.getId(), filterData)) {
            valkeyService.saveDatapoints(node, client.newBinaryFormatReader(response), savedDatapointsStats);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Constructs time-based filters for a ClickHouse query and configures the given ClickHouse
     * request object.
     * The method handles conditions such as start time, end time, grouping, ordering,
     * and limiting of results.
     * These parameters are used to dynamically build the query and set appropriate values
     * for the ClickHouse request.
     *
     * @param f             The RetrieveFilter object containing filter parameters
     *                      such as start time, end time, and limit.
     * @param hasGroupBy    A boolean indicating whether the query includes a GROUP BY clause.
     */
    private ClickHouseFilterData createFilters(
            final RetrieveFilter f,
            boolean hasGroupBy) {

        String filterSQL = "";
        int paramsLength = 3;
        String where = "WHERE timeseries_id = {timeseriesId:Int64} AND ";
        String and = "AND ";
        String startTimeFilter = "";
        ZonedDateTime startTime = null;
        if(f.getStart() != null){
            startTime = f.getStart();
            // Get datapoints starting from and including this time
            startTimeFilter = where + "timestamp >= {startTime:DateTime(3)} ";
        }
        String endTimeFilter = "";
        ZonedDateTime endTime = null;
        if(f.getEnd() != null){
            // Get datapoints up to, but excluding this point in time.
            endTimeFilter = "timestamp < {endTime:DateTime(3)} ";
            if(f.getStart() == null){
                endTimeFilter = where + endTimeFilter;
            } else {
                endTimeFilter = and + endTimeFilter;
                paramsLength = 4;
            }
            endTime = f.getEnd();
        }

        filterSQL = startTimeFilter + endTimeFilter;

        log.debug("ClickHouse filter query: " + filterSQL);
        return new ClickHouseFilterData(filterSQL, null, paramsLength, startTime, endTime, f);
    }

    private QueryResponse createRequest(Client client, long timeseriesId, ClickHouseFilterData filterData)
            throws ExecutionException, InterruptedException, TimeoutException {
        var f = filterData.getF();
        var query = filterData.getQuery();
        log.debug("ClickHouse query: " + query);

        var queryId = IdGenerator.getRandomUUID7AsString();

        QuerySettings qs = new QuerySettings();
        qs.setQueryId(queryId);
        Map<String, Object> queryParams = new HashMap<>();
        queryParams.put("timeseriesId", timeseriesId);
        queryParams.put("limit", filterData.getF().getLimit());

        if (f.getStart() != null) {
            queryParams.put("startTime", toChDateTime(filterData.getStartTime()));
        }
        if (f.getEnd() != null) {
            queryParams.put("endTime", toChDateTime(filterData.getEndTime()));
        }
        log.debug("ClickHouse query parameters for queryId {}: {}", queryId, queryParams);

        return client.query(query, queryParams, qs).get(30, TimeUnit.SECONDS);
    }
}
