// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.clickhouse;

import ai.intellistream.datahub.api.binary.DatapointFrame;
import ai.intellistream.datahub.api.binary.DatapointFrameWriter;
import ai.intellistream.datahub.api.binary.DatapointValueType;
import ai.intellistream.datahub.api.binary.FrameMerger;
import ai.intellistream.datahub.api.binary.PayloadCodec;
import ai.intellistream.datahub.api.binary.ZstdPayloadCodec;
import ai.intellistream.datahub.testsupport.SharedClickHouse;
import com.clickhouse.client.api.Client;
import com.clickhouse.client.api.insert.InsertResponse;
import com.clickhouse.client.api.insert.InsertSettings;
import com.clickhouse.client.api.query.GenericRecord;
import com.clickhouse.data.ClickHouseFormat;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Proves that the Arrow streams the binary datapoint contract produces are what a real ClickHouse
 * server inserts into the datapoint tables as declared in clickhouse.sql: the canonical schema per
 * value type lands without a cast (a DateTime64(3, 'UTC') column, LowCardinality(String) from Utf8,
 * LowCardinality(Nullable(String)) from nullable Utf8, both decimals exact), and a stream merged
 * from several frames inserts as one. Needs a container runtime; run with
 * {@code ./gradlew :datahub-infra:integrationTest}.
 */
@Tag("integration")
class ClickHouseArrowRoundTripIT {

    static final PayloadCodec ZSTD = new ZstdPayloadCodec(3);
    static Client client;

    @BeforeAll
    static void setUp() throws Exception {
        client = SharedClickHouse.newClient("arrow_it");
        ddl("CREATE TABLE datapoints_bigint (timeseries_id Int64, timestamp DateTime64(3, 'UTC'), value Int64) ENGINE=MergeTree ORDER BY (timeseries_id, timestamp)");
        ddl("CREATE TABLE datapoints_float (timeseries_id Int64, timestamp DateTime64(3, 'UTC'), value Float64) ENGINE=MergeTree ORDER BY (timeseries_id, timestamp)");
        ddl("CREATE TABLE datapoints_float32 (timeseries_id Int64, timestamp DateTime64(3, 'UTC'), value Float32) ENGINE=MergeTree ORDER BY (timeseries_id, timestamp)");
        ddl("CREATE TABLE datapoints_numeric (timeseries_id Int64, timestamp DateTime64(3, 'UTC'), value Decimal64(6)) ENGINE=MergeTree ORDER BY (timeseries_id, timestamp)");
        ddl("CREATE TABLE datapoints_text (timeseries_id Int64, timestamp DateTime64(3, 'UTC'), value LowCardinality(String)) ENGINE=MergeTree ORDER BY (timeseries_id, timestamp)");
        ddl("CREATE TABLE datapoints_decimal32 (timeseries_id Int64, timestamp DateTime64(3, 'UTC'), value Decimal32(4)) ENGINE=MergeTree ORDER BY (timeseries_id, timestamp)");
        ddl("CREATE TABLE datapoints_mixed (timeseries_id Int64, timestamp DateTime64(3, 'UTC'), value_numeric Nullable(Float64), value_text LowCardinality(Nullable(String))) ENGINE=MergeTree ORDER BY (timeseries_id, timestamp)");
    }

    @AfterAll
    static void tearDown() {
        if (client != null) client.close();
    }

    static void ddl(String sql) throws Exception {
        SharedClickHouse.execute(client, sql);
    }

    private static void insert(DatapointValueType type, byte[] stream) throws Exception {
        try (InsertResponse ignored = client.insert(type.tableName(), new ByteArrayInputStream(stream),
                ClickHouseFormat.ArrowStream, new InsertSettings()).get(30, TimeUnit.SECONDS)) {
            // inserted
        }
    }

    private static DatapointFrame frame(DatapointValueType type, long firstId, String... values) {
        DatapointFrameWriter w = DatapointFrameWriter.forType(type).series(firstId, "s" + firstId).series(firstId + 1, "s" + (firstId + 1));
        for (int i = 0; i < values.length; i++) {
            long id = firstId + (i % 2);
            w.add(id, 1_700_000_000_000L + i * 1000L, values[i]);
        }
        return DatapointFrame.parseAll(w.build(ZSTD), ZSTD).get(0);
    }

    private static String valueExpr(DatapointValueType type) {
        return switch (type) {
            case TEXT -> "value";
            case MIXED -> "coalesce(toString(value_numeric), value_text)";
            default -> "toString(value)";
        };
    }

    private static List<GenericRecord> rows(DatapointValueType type, long firstId) {
        return client.queryAll("SELECT timeseries_id, toUnixTimestamp64Milli(timestamp) AS ts, " + valueExpr(type)
                + " AS v FROM " + type.tableName() + " WHERE timeseries_id IN (" + firstId + ", " + (firstId + 1)
                + ") ORDER BY timeseries_id, timestamp");
    }

    private static void assertSameValue(DatapointValueType type, String expected, String actual) {
        switch (type) {
            case FLOAT, FLOAT32 -> assertEquals(Double.parseDouble(expected), Double.parseDouble(actual), 1e-6);
            case NUMERIC, DECIMAL32, BIGINT -> assertEquals(0, new BigDecimal(expected).compareTo(new BigDecimal(actual)),
                    "expected " + expected + " but read " + actual);
            case MIXED -> {
                try {
                    assertEquals(Double.parseDouble(expected), Double.parseDouble(actual), 1e-9);
                } catch (NumberFormatException textual) {
                    assertEquals(expected, actual);
                }
            }
            case TEXT -> assertEquals(expected, actual);
        }
    }

    private static void roundTrip(DatapointValueType type, long firstId, String... values) throws Exception {
        DatapointFrame f = frame(type, firstId, values);
        insert(type, FrameMerger.merge(type, List.of(f), 1_000_000));
        List<GenericRecord> read = rows(type, firstId);
        assertEquals(f.rowCount(), read.size(), type + " row count");
        for (int r = 0; r < f.rowCount(); r++) {
            assertEquals(f.id(r), read.get(r).getLong("timeseries_id"), type + " id at row " + r);
            assertEquals(f.timestamp(r), read.get(r).getLong("ts"), type + " timestamp at row " + r);
            assertSameValue(type, f.valueAsString(r), read.get(r).getString("v"));
        }
    }

    @Test
    void bigint() throws Exception {
        roundTrip(DatapointValueType.BIGINT, 100, "12345", "-7", "9223372036854775807");
    }

    @Test
    void floatType() throws Exception {
        roundTrip(DatapointValueType.FLOAT, 200, "3.5", "-0.25", "1e10");
    }

    @Test
    void float32() throws Exception {
        roundTrip(DatapointValueType.FLOAT32, 300, "3.5", "22.4", "-1");
    }

    @Test
    void numericIsExact() throws Exception {
        roundTrip(DatapointValueType.NUMERIC, 400, "344.544", "12.3456789", "-0.000001");
    }

    @Test
    void decimal32RoundsAndClamps() throws Exception {
        roundTrip(DatapointValueType.DECIMAL32, 500, "1.23456", "250000.5", "-3");
    }

    @Test
    void textIntoLowCardinality() throws Exception {
        roundTrip(DatapointValueType.TEXT, 600, "FAULT", "ok", "høyde over havet");
    }

    @Test
    void mixedIntoNullableAndLowCardinality() throws Exception {
        roundTrip(DatapointValueType.MIXED, 700, "23.5", "FAULT", "-1", "open");
    }

    @Test
    void timestampColumnIsUtcAndArrivesUncast() {
        List<GenericRecord> type = client.queryAll("SELECT toTypeName(timestamp) AS t FROM datapoints_float32 LIMIT 1");
        assertEquals("DateTime64(3, 'UTC')", type.get(0).getString("t"));
    }

    @Test
    void mergedFramesInsertAsOneStream() throws Exception {
        List<DatapointFrame> frames = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            DatapointFrameWriter w = DatapointFrameWriter.forType(DatapointValueType.FLOAT32).series(800 + i, "m" + i);
            for (int r = 0; r < 250; r++) {
                w.addFloat32(800 + i, r * 1000L, i * 1000 + r);
            }
            frames.add(DatapointFrame.parseAll(w.build(ZSTD), ZSTD).get(0));
        }
        insert(DatapointValueType.FLOAT32, FrameMerger.merge(DatapointValueType.FLOAT32, frames, 600));
        List<GenericRecord> count = client.queryAll(
                "SELECT count() AS c, sum(value) AS s FROM datapoints_float32 WHERE timeseries_id BETWEEN 800 AND 803");
        assertEquals(1000, count.get(0).getLong("c"));
        double expected = 0;
        for (int i = 0; i < 4; i++) for (int r = 0; r < 250; r++) expected += i * 1000 + r;
        assertEquals(expected, count.get(0).getDouble("s"), 0.5);
    }
}
