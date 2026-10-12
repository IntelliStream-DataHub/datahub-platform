// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.clickhouse;

import ai.intellistream.datahub.api.binary.DatapointFrame;
import ai.intellistream.datahub.api.binary.DatapointFrameWriter;
import ai.intellistream.datahub.api.binary.DatapointValueType;
import ai.intellistream.datahub.api.binary.FrameLimits;
import ai.intellistream.datahub.api.binary.FrameMerger;
import ai.intellistream.datahub.api.binary.PayloadCodec;
import ai.intellistream.datahub.api.binary.ZstdPayloadCodec;
import ai.intellistream.datahub.jpa.domains.TimeseriesEntity;
import ai.intellistream.datahub.jpa.domains.TimeseriesValueType;
import ai.intellistream.datahub.models.forms.RetrieveFilter;
import ai.intellistream.datahub.services.ValkeyService;
import ai.intellistream.datahub.tenant.Tenant;
import ai.intellistream.datahub.tenant.TenantConfigService;
import ai.intellistream.datahub.testsupport.SharedClickHouse;
import com.clickhouse.client.api.Client;
import com.clickhouse.client.api.insert.InsertResponse;
import com.clickhouse.client.api.insert.InsertSettings;
import com.clickhouse.data.ClickHouseFormat;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The binary read against a real ClickHouse: what {@link ClickHouseDatapointService#openArrowStream}
 * returns is the canon for every value type and for aggregates, its buffers compressed in-band, so
 * the API relays it into frames without decoding a row, and the frames read back as the points
 * that were written, and write back through the consumer's path. Also pins what the query has to
 * get right on the server's side: a re-sent point read once ({@code FINAL}), a legacy table
 * without a zone on its timestamp, millisecond window bounds, and a series longer than a frame
 * answered in blocks that fit one. Needs a container runtime; run with
 * {@code ./gradlew :datahub-infra:integrationTest}.
 */
@Tag("integration")
class ClickHouseArrowReadIT {

    static final PayloadCodec ZSTD = new ZstdPayloadCodec(3);
    static final long T0 = 1_700_000_000_000L;
    static Client client;
    static ClickHouseDatapointService service;

    @BeforeAll
    static void setUp() throws Exception {
        client = SharedClickHouse.newClient("arrow_read_it");
        String engine = " ENGINE=ReplacingMergeTree ORDER BY (timeseries_id, timestamp)";
        // Bare DateTime64(3), as tables created before clickhouse.sql named the zone still are.
        ddl("CREATE TABLE datapoints_bigint (timeseries_id Int64, timestamp DateTime64(3), value Int64)" + engine);
        ddl("CREATE TABLE datapoints_float (timeseries_id Int64, timestamp DateTime64(3, 'UTC'), value Float64)" + engine);
        ddl("CREATE TABLE datapoints_float32 (timeseries_id Int64, timestamp DateTime64(3, 'UTC'), value Float32)" + engine);
        ddl("CREATE TABLE datapoints_numeric (timeseries_id Int64, timestamp DateTime64(3, 'UTC'), value Decimal64(6))" + engine);
        ddl("CREATE TABLE datapoints_text (timeseries_id Int64, timestamp DateTime64(3, 'UTC'), value LowCardinality(String))" + engine);
        ddl("CREATE TABLE datapoints_decimal32 (timeseries_id Int64, timestamp DateTime64(3, 'UTC'), value Decimal32(4))" + engine);
        ddl("CREATE TABLE datapoints_mixed (timeseries_id Int64, timestamp DateTime64(3, 'UTC'), value_numeric Nullable(Float64), value_text LowCardinality(Nullable(String)))" + engine);

        TenantConfigService tenantConfigService = mock(TenantConfigService.class);
        ClickHouseClientPool pool = mock(ClickHouseClientPool.class);
        when(tenantConfigService.getConfig(anyString())).thenReturn(new Tenant());
        when(pool.getClient(any(Tenant.class))).thenReturn(client);
        service = new ClickHouseDatapointService(tenantConfigService, mock(ValkeyService.class), pool);
    }

    @AfterAll
    static void tearDown() {
        if (client != null) client.close();
    }

    static void ddl(String sql) throws Exception {
        SharedClickHouse.execute(client, sql);
    }

    private static DatapointFrame write(DatapointValueType type, long id, String... values) throws Exception {
        DatapointFrameWriter w = DatapointFrameWriter.forType(type).series(id, "s" + id);
        for (int i = 0; i < values.length; i++) {
            w.add(id, T0 + i * 1000L, values[i]);
        }
        DatapointFrame f = DatapointFrame.parseAll(w.build(ZSTD), ZSTD).get(0);
        insert(type, FrameMerger.merge(type, List.of(f), 1_000_000));
        return f;
    }

    private static void insert(DatapointValueType type, byte[] stream) throws Exception {
        try (InsertResponse ignored = client.insert(type.tableName(), new ByteArrayInputStream(stream),
                ClickHouseFormat.ArrowStream, new InsertSettings()).get(30, TimeUnit.SECONDS)) {
            // inserted
        }
    }

    private static TimeseriesEntity series(DatapointValueType type, long id) {
        TimeseriesEntity ts = new TimeseriesEntity();
        ts.setId(id);
        ts.setExternalId("s" + id);
        ts.setValueType(new TimeseriesValueType(type.id()));
        return ts;
    }

    private static RetrieveFilter window(Long startMillis, Long endMillis) {
        RetrieveFilter f = new RetrieveFilter();
        if (startMillis != null) f.setStart(Instant.ofEpochMilli(startMillis).atZone(ZoneOffset.UTC));
        if (endMillis != null) f.setEnd(Instant.ofEpochMilli(endMillis).atZone(ZoneOffset.UTC));
        return f;
    }

    /** Fetches and relays the answer into frames the way the API does, as one response body; a limit of 0 is none. */
    private static byte[] readBody(DatapointValueType type, long id, RetrieveFilter f, long limit) throws Exception {
        boolean aggregates = !f.getAggregates().isEmpty();
        int frameRows = aggregates ? FrameLimits.MAX_AGGREGATE_ROWS : FrameLimits.maxRows(type);
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        try (InputStream stream = service.openArrowStream("t", series(type, id), f, limit, frameRows)) {
            DatapointFrameWriter.relayStream(type, aggregates, id, "s" + id, stream, ZSTD, body);
        }
        body.writeBytes(FrameLimits.END_OF_RESPONSE);
        return body.toByteArray();
    }

    private static List<DatapointFrame> read(DatapointValueType type, long id, RetrieveFilter f, long limit) throws Exception {
        List<DatapointFrame> frames = DatapointFrame.parseResponse(readBody(type, id, f, limit), ZSTD);
        for (DatapointFrame frame : frames) {
            assertEquals(FrameLimits.COMPRESSION_ARROW_ZSTD, frame.compression(), "relayed as ClickHouse compressed it");
        }
        return frames;
    }

    private static List<String> values(List<DatapointFrame> frames) {
        List<String> out = new ArrayList<>();
        for (DatapointFrame f : frames) {
            for (int r = 0; r < f.rowCount(); r++) out.add(f.valueAsString(r));
        }
        return out;
    }

    private static List<Long> timestamps(List<DatapointFrame> frames) {
        List<Long> out = new ArrayList<>();
        for (DatapointFrame f : frames) {
            for (int r = 0; r < f.rowCount(); r++) out.add(f.timestamp(r));
        }
        return out;
    }

    private static void roundTrip(DatapointValueType type, long id, String... values) throws Exception {
        DatapointFrame written = write(type, id, values);
        List<DatapointFrame> frames = read(type, id, window(null, null), 1000);
        List<String> expected = new ArrayList<>();
        for (int r = 0; r < written.rowCount(); r++) expected.add(written.valueAsString(r));
        assertEquals(expected, values(frames), type.toString());
        assertEquals(List.of(T0, T0 + 1000, T0 + 2000).subList(0, values.length), timestamps(frames), type + " timestamps");
        assertEquals(id, frames.get(0).seriesIds()[0]);
        assertEquals("s" + id, frames.get(0).externalIds()[0]);
    }

    @Test
    void bigintFromATableWithoutAZone() throws Exception {
        roundTrip(DatapointValueType.BIGINT, 100, "12345", "-7", "9223372036854775807");
    }

    @Test
    void floatType() throws Exception {
        roundTrip(DatapointValueType.FLOAT, 200, "3.5", "-0.25", "1.0E10");
    }

    @Test
    void float32() throws Exception {
        roundTrip(DatapointValueType.FLOAT32, 300, "3.5", "22.4", "-1.0");
    }

    @Test
    void numericIsExact() throws Exception {
        roundTrip(DatapointValueType.NUMERIC, 400, "344.544", "12.345679", "-0.000001");
    }

    @Test
    void decimal32() throws Exception {
        roundTrip(DatapointValueType.DECIMAL32, 500, "1.2346", "-3");
    }

    @Test
    void text() throws Exception {
        roundTrip(DatapointValueType.TEXT, 600, "FAULT", "ok", "høyde over havet");
    }

    @Test
    void mixedWithBothKindsAndWithNumbersOnly() throws Exception {
        roundTrip(DatapointValueType.MIXED, 700, "23.5", "FAULT", "-1.0");
        // No text at all: the text column has only nulls and the numeric none.
        roundTrip(DatapointValueType.MIXED, 701, "1.5", "2.5");
    }

    @Test
    void aResentPointIsReadOnce() throws Exception {
        write(DatapointValueType.FLOAT, 800, "1", "2", "3");
        write(DatapointValueType.FLOAT, 800, "1", "2", "3");
        assertEquals(3, timestamps(read(DatapointValueType.FLOAT, 800, window(null, null), 1000)).size());
    }

    @Test
    void theWindowIsMillisecondExactAndTheLimitHolds() throws Exception {
        write(DatapointValueType.FLOAT, 900, "1", "2", "3", "4", "5");
        // start inclusive, end exclusive, both one millisecond off a point.
        assertEquals(List.of(T0 + 2000, T0 + 3000), timestamps(read(DatapointValueType.FLOAT, 900, window(T0 + 1001, T0 + 3001), 1000)));
        assertEquals(List.of(T0, T0 + 1000), timestamps(read(DatapointValueType.FLOAT, 900, window(null, null), 2)));
        // No limit: the whole window.
        assertEquals(5, timestamps(read(DatapointValueType.FLOAT, 900, window(null, null), 0)).size());
        // Paging: start one past the last timestamp read.
        assertEquals(List.of(T0 + 2000, T0 + 3000), timestamps(read(DatapointValueType.FLOAT, 900, window(T0 + 1001, null), 2)));
    }

    @Test
    void anEmptyWindowIsNoFrames() throws Exception {
        write(DatapointValueType.FLOAT, 1000, "1");
        assertTrue(read(DatapointValueType.FLOAT, 1000, window(T0 + 5000, null), 1000).isEmpty());
        assertTrue(read(DatapointValueType.FLOAT, 999_999, window(null, null), 1000).isEmpty());
    }

    @Test
    void aSeriesLongerThanAFrameComesInBlocksThatFitOne() throws Exception {
        // 25 000 text rows against a 10 000-row frame cap: ClickHouse is asked for 8 192-row
        // blocks, one granule, since 10 000 would come back as 16 384 and have to be cut.
        int rows = 25_000;
        List<DatapointFrame> parts = new ArrayList<>();
        for (int start = 0; start < rows; start += 10_000) {
            DatapointFrameWriter w = DatapointFrameWriter.forType(DatapointValueType.TEXT).series(1100, "s1100");
            for (int r = start; r < Math.min(rows, start + 10_000); r++) {
                w.addText(1100, T0 + r, "v" + (r % 7));
            }
            parts.add(DatapointFrame.parseAll(w.build(ZSTD), ZSTD).get(0));
        }
        for (DatapointFrame part : parts) {
            insert(DatapointValueType.TEXT, FrameMerger.merge(DatapointValueType.TEXT, List.of(part), 1_000_000));
        }
        List<DatapointFrame> frames = read(DatapointValueType.TEXT, 1100, window(null, null), 0);
        assertEquals(rows, timestamps(frames).size());
        assertTrue(frames.size() >= 3, frames.size() + " frames");
        for (DatapointFrame frame : frames) {
            assertTrue(frame.rowCount() <= 10_000, frame.rowCount() + " rows");
            assertTrue(frame.rowCount() >= 1000, frame.rowCount() + " rows: blocks are packed, not one a frame");
        }
        assertEquals("v" + (24_999 % 7), frames.get(frames.size() - 1).valueAsString(frames.get(frames.size() - 1).rowCount() - 1));
    }

    @Test
    void aReadsAnswerWritesBackThroughTheConsumersPath() throws Exception {
        // What the API answers, posted back: ingest's parse takes the body with its end marker, the
        // consumer merges the frames and inserts them. The rows are deleted in between so the
        // re-insert is what the second read finds.
        write(DatapointValueType.MIXED, 1500, "1.5", "FAULT", "2.5");
        byte[] answer = readBody(DatapointValueType.MIXED, 1500, window(null, null), 0);
        List<DatapointFrame> frames = DatapointFrame.parseAll(answer, ZSTD);
        assertEquals(1, frames.size());
        assertEquals(List.of(1500L), List.of(frames.get(0).runs().get(0).id()));

        ddl("DELETE FROM datapoints_mixed WHERE timeseries_id = 1500");
        assertTrue(read(DatapointValueType.MIXED, 1500, window(null, null), 0).isEmpty());
        service.insertArrowStream("t", DatapointValueType.MIXED, FrameMerger.merge(DatapointValueType.MIXED, frames, 1_000_000));
        List<DatapointFrame> again = read(DatapointValueType.MIXED, 1500, window(null, null), 0);
        assertEquals(List.of("1.5", "FAULT", "2.5"), values(again));
        assertEquals(List.of(T0, T0 + 1000, T0 + 2000), timestamps(again));
    }

    @Test
    void aggregatesAreTheirOwnCanon() throws Exception {
        // 0, 10, 20 at one-minute steps inside one hour, held to the hour's end.
        DatapointFrameWriter w = DatapointFrameWriter.forType(DatapointValueType.FLOAT).series(1200, "s1200");
        long hour = 1_700_002_800_000L; // 2023-11-14T23:00:00Z, on an hour boundary
        w.addFloat(1200, hour, 0);
        w.addFloat(1200, hour + 60_000, 10);
        w.addFloat(1200, hour + 120_000, 20);
        insert(DatapointValueType.FLOAT, FrameMerger.merge(DatapointValueType.FLOAT,
                List.of(DatapointFrame.parseAll(w.build(ZSTD), ZSTD).get(0)), 1_000_000));

        RetrieveFilter f = window(hour, hour + 3_600_000);
        f.setAggregates(List.of("avg"));
        f.setGranularity("1h");
        List<DatapointFrame> frames = read(DatapointValueType.FLOAT, 1200, f, 1000);

        assertEquals(1, frames.size());
        DatapointFrame frame = frames.get(0);
        assertTrue(frame.aggregates());
        assertEquals(1, frame.rowCount());
        assertEquals(hour, frame.timestamp(0));
        assertEquals(0, frame.min(0));
        assertEquals(20, frame.max(0));
        assertEquals(30, frame.sum(0));
        // One minute at 0, one at 10, 58 at 20.
        assertEquals((0 * 1 + 10 * 1 + 20 * 58) / 60.0, frame.avg(0), 1e-9);
    }

    @Test
    void aggregatesLongerThanAFrameComeInFramesUnderTheCap() throws Exception {
        // 60 000 one-second buckets against a 50 000-row aggregate frame cap.
        int rows = 60_000;
        for (int start = 0; start < rows; start += 50_000) {
            DatapointFrameWriter w = DatapointFrameWriter.forType(DatapointValueType.FLOAT).series(1400, "s1400");
            for (int r = start; r < Math.min(rows, start + 50_000); r++) {
                w.addFloat(1400, T0 + r * 1000L, r);
            }
            insert(DatapointValueType.FLOAT, FrameMerger.merge(DatapointValueType.FLOAT,
                    List.of(DatapointFrame.parseAll(w.build(ZSTD), ZSTD).get(0)), 1_000_000));
        }
        RetrieveFilter f = window(T0, T0 + rows * 1000L);
        f.setAggregates(List.of("avg"));
        f.setGranularity("1s");
        List<DatapointFrame> frames = read(DatapointValueType.FLOAT, 1400, f, 0);
        assertEquals(rows, timestamps(frames).size());
        assertTrue(frames.size() >= 2, frames.size() + " frames");
        for (DatapointFrame frame : frames) {
            assertTrue(frame.rowCount() <= FrameLimits.MAX_AGGREGATE_ROWS, frame.rowCount() + " rows");
        }
        assertEquals(T0 + (rows - 1) * 1000L, frames.get(frames.size() - 1).timestamp(frames.get(frames.size() - 1).rowCount() - 1));
    }

    @Test
    void mixedAggregatesSkipTheTextRows() throws Exception {
        DatapointFrameWriter w = DatapointFrameWriter.forType(DatapointValueType.MIXED).series(1300, "s1300");
        long hour = 1_700_002_800_000L;
        w.addMixed(1300, hour, "4");
        w.addMixed(1300, hour + 60_000, "FAULT");
        insert(DatapointValueType.MIXED, FrameMerger.merge(DatapointValueType.MIXED,
                List.of(DatapointFrame.parseAll(w.build(ZSTD), ZSTD).get(0)), 1_000_000));

        RetrieveFilter f = window(hour, hour + 3_600_000);
        f.setAggregates(List.of("max"));
        f.setGranularity("1h");
        DatapointFrame frame = read(DatapointValueType.MIXED, 1300, f, 1000).get(0);
        assertEquals(4, frame.max(0));
        assertEquals(4, frame.sum(0));
    }
}
