// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.clickhouse;

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
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.math.BigDecimal;
import java.util.List;

import static ai.intellistream.datahub.jpa.domains.TimeseriesValueType.BIGINT;
import static ai.intellistream.datahub.jpa.domains.TimeseriesValueType.DECIMAL32;
import static ai.intellistream.datahub.jpa.domains.TimeseriesValueType.FLOAT;
import static ai.intellistream.datahub.jpa.domains.TimeseriesValueType.FLOAT32;
import static ai.intellistream.datahub.jpa.domains.TimeseriesValueType.MIXED;
import static ai.intellistream.datahub.jpa.domains.TimeseriesValueType.NUMERIC;
import static ai.intellistream.datahub.jpa.domains.TimeseriesValueType.TEXT;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Proves that {@link DatapointValueCodec}'s bytes are valid ClickHouse RowBinary a real server
 * accepts and reads back correctly, for every value type — i.e. the producer-side binary encoding
 * matches what ClickHouse expects. Needs a container runtime; run with
 * {@code ./gradlew :datahub-infra:integrationTest} on a host with Docker/Podman.
 *
 * <p>Column types mirror clickhouse.sql; the engine/codecs/partitioning are simplified because only
 * the column types affect RowBinary parsing.
 */
@Tag("integration")
class ClickHouseDatapointRoundTripIT {

    static Client client;

    @BeforeAll
    static void setUp() throws Exception {
        client = SharedClickHouse.newClient("roundtrip_it");
        ddl("CREATE TABLE datapoints_bigint (timeseries_id Int64, timestamp DateTime64(3), value Int64) ENGINE=MergeTree ORDER BY (timeseries_id, timestamp)");
        ddl("CREATE TABLE datapoints_float (timeseries_id Int64, timestamp DateTime64(3), value Float64) ENGINE=MergeTree ORDER BY (timeseries_id, timestamp)");
        ddl("CREATE TABLE datapoints_float32 (timeseries_id Int64, timestamp DateTime64(3), value Float32) ENGINE=MergeTree ORDER BY (timeseries_id, timestamp)");
        ddl("CREATE TABLE datapoints_numeric (timeseries_id Int64, timestamp DateTime64(3), value Decimal64(6)) ENGINE=MergeTree ORDER BY (timeseries_id, timestamp)");
        ddl("CREATE TABLE datapoints_text (timeseries_id Int64, timestamp DateTime64(3), value LowCardinality(String)) ENGINE=MergeTree ORDER BY (timeseries_id, timestamp)");
        ddl("CREATE TABLE datapoints_decimal32 (timeseries_id Int64, timestamp DateTime64(3), value Decimal32(4)) ENGINE=MergeTree ORDER BY (timeseries_id, timestamp)");
        ddl("CREATE TABLE datapoints_mixed (timeseries_id Int64, timestamp DateTime64(3), value_numeric Nullable(Float64), value_text LowCardinality(Nullable(String))) ENGINE=MergeTree ORDER BY (timeseries_id, timestamp)");
    }

    @AfterAll
    static void tearDown() {
        if (client != null) client.close();
    }

    static void ddl(String sql) throws Exception {
        SharedClickHouse.execute(client, sql);
    }

    /** Frame one RowBinary row (timeseries_id, timestamp, then the codec's value bytes) and insert it. */
    private static void insert(String table, long tsId, byte[] valueBytes) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(bos);
        dos.writeLong(Long.reverseBytes(tsId));
        dos.writeLong(Long.reverseBytes(1_700_000_000_000L)); // DateTime64(3): epoch millis, little-endian
        dos.write(valueBytes);
        dos.flush();
        try (InsertResponse ignored = client.insert(table, new ByteArrayInputStream(bos.toByteArray()),
                ClickHouseFormat.RowBinary, new InsertSettings()).get()) {
            // row inserted
        }
    }

    private static String read(String table, String valueExpr, long tsId) {
        List<GenericRecord> rows = client.queryAll(
                "SELECT " + valueExpr + " AS v FROM " + table + " WHERE timeseries_id = " + tsId);
        return rows.get(0).getString("v");
    }

    private static void assertNumeric(String expected, String actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(new BigDecimal(actual)),
                "expected " + expected + " but read " + actual);
    }

    @Test
    void bigint() throws Exception {
        insert("datapoints_bigint", 1, DatapointValueCodec.encode("12345", BIGINT));
        assertNumeric("12345", read("datapoints_bigint", "toString(value)", 1));
    }

    @Test
    void floatType() throws Exception {
        insert("datapoints_float", 2, DatapointValueCodec.encode("3.5", FLOAT));
        assertEquals(3.5, Double.parseDouble(read("datapoints_float", "toString(value)", 2)), 0.0);
    }

    @Test
    void float32() throws Exception {
        insert("datapoints_float32", 9, DatapointValueCodec.encode("3.5", FLOAT32));
        assertEquals(3.5, Double.parseDouble(read("datapoints_float32", "toString(value)", 9)), 0.0);
    }

    @Test
    void numeric() throws Exception {
        insert("datapoints_numeric", 3, DatapointValueCodec.encode("344.544", NUMERIC));
        assertNumeric("344.544", read("datapoints_numeric", "toString(value)", 3));
    }

    @Test
    void text() throws Exception {
        insert("datapoints_text", 4, DatapointValueCodec.encode("FAULT", TEXT));
        assertEquals("FAULT", read("datapoints_text", "value", 4));
    }

    @Test
    void decimal32RoundsAndClamps() throws Exception {
        insert("datapoints_decimal32", 5, DatapointValueCodec.encode("1.23456", DECIMAL32));   // rounds to 1.2346
        assertNumeric("1.2346", read("datapoints_decimal32", "toString(value)", 5));
        insert("datapoints_decimal32", 6, DatapointValueCodec.encode("250000.5", DECIMAL32));   // clamps to rail
        assertNumeric("99999.9999", read("datapoints_decimal32", "toString(value)", 6));
    }

    @Test
    void mixedNumericAndText() throws Exception {
        insert("datapoints_mixed", 7, DatapointValueCodec.encode("23.5", MIXED));
        insert("datapoints_mixed", 8, DatapointValueCodec.encode("FAULT", MIXED));
        String numericExpr = "coalesce(toString(value_numeric), value_text)";
        assertEquals(23.5, Double.parseDouble(read("datapoints_mixed", numericExpr, 7)), 0.0);
        assertEquals("FAULT", read("datapoints_mixed", numericExpr, 8));
    }
}
