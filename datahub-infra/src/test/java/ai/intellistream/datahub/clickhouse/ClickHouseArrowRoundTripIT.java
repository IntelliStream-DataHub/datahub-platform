// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.clickhouse;

import ai.intellistream.datahub.api.binary.ArrowIpc;
import ai.intellistream.datahub.api.binary.ArrowSchemaCanon;
import ai.intellistream.datahub.api.binary.DatapointFrame;
import ai.intellistream.datahub.api.binary.DatapointFrameWriter;
import ai.intellistream.datahub.api.binary.DatapointValueType;
import ai.intellistream.datahub.api.binary.FrameLimits;
import ai.intellistream.datahub.api.binary.FrameMerger;
import ai.intellistream.datahub.api.binary.PayloadCodec;
import ai.intellistream.datahub.api.binary.ZstdPayloadCodec;
import ai.intellistream.datahub.services.ValkeyService;
import ai.intellistream.datahub.tenant.Tenant;
import ai.intellistream.datahub.tenant.TenantConfigService;
import ai.intellistream.datahub.testsupport.SharedClickHouse;
import com.clickhouse.client.api.Client;
import com.clickhouse.client.api.query.GenericRecord;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Proves that the Arrow streams the binary datapoint contract produces are what a real ClickHouse
 * server inserts into the datapoint tables as declared in clickhouse.sql: the canonical schema per
 * value type lands without a cast (a DateTime64(3, 'UTC') column, LowCardinality(String) from Utf8,
 * LowCardinality(Nullable(String)) from nullable Utf8, both decimals exact), with the record
 * batches relayed as the SDK sent them, every buffer zstd-compressed in-band and the HTTP body
 * uncompressed, and that the batches of several frames insert as one stream, compressed and
 * uncompressed alike. Goes through {@code insertArrowStream}, so the transport is production's.
 * Needs a container runtime; run with {@code ./gradlew :datahub-infra:integrationTest}.
 */
@Tag("integration")
class ClickHouseArrowRoundTripIT {

    static final PayloadCodec ZSTD = new ZstdPayloadCodec(3);
    static Client client;
    static ClickHouseDatapointService service;
    /** As production's pool: inserts go in asynchronously, so a part is formed from a flush. */
    static Client asyncClient;
    static ClickHouseDatapointService asyncService;

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

        TenantConfigService tenantConfigService = mock(TenantConfigService.class);
        ClickHouseClientPool pool = mock(ClickHouseClientPool.class);
        when(tenantConfigService.getConfig(anyString())).thenReturn(new Tenant());
        when(pool.getClient(any(Tenant.class))).thenReturn(client);
        service = new ClickHouseDatapointService(tenantConfigService, mock(ValkeyService.class), pool);
        asyncClient = SharedClickHouse.clientBuilder("arrow_it").serverSetting("async_insert", "1").build();
        ClickHouseClientPool asyncPool = mock(ClickHouseClientPool.class);
        when(asyncPool.getClient(any(Tenant.class))).thenReturn(asyncClient);
        asyncService = new ClickHouseDatapointService(tenantConfigService, mock(ValkeyService.class), asyncPool);
    }

    @AfterAll
    static void tearDown() {
        if (client != null) client.close();
        if (asyncClient != null) asyncClient.close();
    }

    static void ddl(String sql) throws Exception {
        SharedClickHouse.execute(client, sql);
    }

    /** As the consumer inserts: the frames' batches relayed into one stream, the body uncompressed when every frame was in-band. */
    private static void insert(DatapointValueType type, List<DatapointFrame> frames) {
        insert(service, type, frames);
    }

    private static void insert(ClickHouseDatapointService svc, DatapointValueType type, List<DatapointFrame> frames) {
        List<byte[]> batches = new ArrayList<>();
        boolean compressed = true;
        for (DatapointFrame f : frames) {
            batches.add(FrameMerger.batches(f, ZSTD));
            compressed &= f.compression() == FrameLimits.COMPRESSION_ARROW_ZSTD;
        }
        svc.insertArrowStream("t", type, FrameMerger.stream(type, batches), compressed);
    }

    private static long activeParts(String table) {
        return client.queryAll("SELECT count() AS c FROM system.parts WHERE database = currentDatabase() AND table = '"
                + table + "' AND active").get(0).getLong("c");
    }

    /**
     * Fifty frames of a hundred rows, relayed as fifty small record batches, form one part: the
     * consumer's merge into million-row blocks used to do this, and ClickHouse's own squashing of an
     * insert's blocks does it now, with the batches' buffers still compressed. Checked both ways
     * production could be configured, synchronous and asynchronous inserts.
     */
    private static void smallBatchesFormOnePart(ClickHouseDatapointService svc, long firstId) {
        List<DatapointFrame> frames = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            DatapointFrameWriter w = DatapointFrameWriter.forType(DatapointValueType.FLOAT32).series(firstId + i, "p" + i);
            for (int r = 0; r < 100; r++) {
                w.addFloat32(firstId + i, r * 1000L, r);
            }
            frames.add(DatapointFrame.parseAll(w.build(ZSTD), ZSTD).get(0));
        }
        long before = activeParts("datapoints_float32");
        insert(svc, DatapointValueType.FLOAT32, frames);
        assertEquals(1, activeParts("datapoints_float32") - before, "parts formed by one insert of 50 batches");
        List<GenericRecord> count = client.queryAll("SELECT count() AS c FROM datapoints_float32 WHERE timeseries_id BETWEEN "
                + firstId + " AND " + (firstId + 49));
        assertEquals(5000, count.get(0).getLong("c"));
    }

    @Test
    void fiftySmallBatchesFormOnePart() {
        smallBatchesFormOnePart(service, 2000);
    }

    @Test
    void fiftySmallBatchesFormOnePartAsynchronously() {
        smallBatchesFormOnePart(asyncService, 3000);
    }

    private static DatapointFrame frame(DatapointValueType type, long firstId, String... values) {
        DatapointFrameWriter w = DatapointFrameWriter.forType(type).series(firstId, "s" + firstId).series(firstId + 1, "s" + (firstId + 1));
        for (int i = 0; i < values.length; i++) {
            long id = firstId + (i % 2);
            w.add(id, 1_700_000_000_000L + i * 1000L, values[i]);
        }
        return DatapointFrame.parseAll(w.build(ZSTD), ZSTD).get(0);
    }

    /** The same rows as a {@link FrameLimits#COMPRESSION_ZSTD} frame: the whole stream zstd, its buffers plain. */
    private static DatapointFrame wholePayloadFrame(DatapointFrame inBand) {
        ArrowSchemaCanon canon = ArrowSchemaCanon.of(inBand.valueType());
        byte[] raw = ArrowIpc.writeStream(canon, inBand.batches().stream().map(ArrowIpc.Batch::toData).toList());
        byte[] compressed = ZSTD.compress(raw);
        long[] ids = inBand.seriesIds();
        String[] names = inBand.externalIds();
        int directoryLength = 0;
        for (String n : names) {
            directoryLength += 8 + 1 + n.getBytes(StandardCharsets.UTF_8).length;
        }
        ByteBuffer f = ByteBuffer.allocate(FrameLimits.HEADER_BYTES + directoryLength + compressed.length).order(ByteOrder.LITTLE_ENDIAN);
        f.put(FrameLimits.MAGIC).put((byte) FrameLimits.VERSION).put((byte) inBand.valueType().id())
                .put((byte) FrameLimits.CODEC_ARROW_IPC).put((byte) FrameLimits.COMPRESSION_ZSTD);
        f.putInt(inBand.rowCount()).putInt(ids.length).putInt(directoryLength).putInt(compressed.length).putInt(raw.length);
        for (int s = 0; s < ids.length; s++) {
            byte[] name = names[s].getBytes(StandardCharsets.UTF_8);
            f.putLong(ids[s]).put((byte) name.length).put(name);
        }
        f.put(compressed);
        return DatapointFrame.parseAll(f.array(), ZSTD).get(0);
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

    private static void assertStored(DatapointFrame f, long firstId) {
        List<GenericRecord> read = rows(f.valueType(), firstId);
        assertEquals(f.rowCount(), read.size(), f.valueType() + " row count");
        for (int r = 0; r < f.rowCount(); r++) {
            assertEquals(f.id(r), read.get(r).getLong("timeseries_id"), f.valueType() + " id at row " + r);
            assertEquals(f.timestamp(r), read.get(r).getLong("ts"), f.valueType() + " timestamp at row " + r);
            assertSameValue(f.valueType(), f.valueAsString(r), read.get(r).getString("v"));
        }
    }

    /** The frame as the SDK sends it, its buffers compressed in-band, relayed to ClickHouse as it is. */
    private static void roundTrip(DatapointValueType type, long firstId, String... values) {
        DatapointFrame f = frame(type, firstId, values);
        assertEquals(FrameLimits.COMPRESSION_ARROW_ZSTD, f.compression());
        insert(type, List.of(f));
        assertStored(f, firstId);
    }

    @Test
    void bigint() {
        roundTrip(DatapointValueType.BIGINT, 100, "12345", "-7", "9223372036854775807");
    }

    @Test
    void floatType() {
        roundTrip(DatapointValueType.FLOAT, 200, "3.5", "-0.25", "1e10");
    }

    @Test
    void float32() {
        roundTrip(DatapointValueType.FLOAT32, 300, "3.5", "22.4", "-1");
    }

    @Test
    void numericIsExact() {
        roundTrip(DatapointValueType.NUMERIC, 400, "344.544", "12.3456789", "-0.000001");
    }

    @Test
    void decimal32RoundsAndClamps() {
        roundTrip(DatapointValueType.DECIMAL32, 500, "1.23456", "250000.5", "-3");
    }

    @Test
    void textIntoLowCardinality() {
        roundTrip(DatapointValueType.TEXT, 600, "FAULT", "ok", "høyde over havet");
    }

    @Test
    void mixedIntoNullableAndLowCardinality() {
        roundTrip(DatapointValueType.MIXED, 700, "23.5", "FAULT", "-1", "open");
    }

    @Test
    void aWholePayloadFrameRelaysItsPlainBuffers() {
        DatapointFrame f = wholePayloadFrame(frame(DatapointValueType.MIXED, 900, "1.5", "closed", "2"));
        assertEquals(FrameLimits.COMPRESSION_ZSTD, f.compression());
        insert(DatapointValueType.MIXED, List.of(f));
        assertStored(f, 900);
    }

    @Test
    void timestampColumnIsUtcAndArrivesUncast() {
        List<GenericRecord> type = client.queryAll("SELECT toTypeName(timestamp) AS t FROM datapoints_float32 LIMIT 1");
        assertEquals("DateTime64(3, 'UTC')", type.get(0).getString("t"));
    }

    /** Frames of both compressions in one stream: the batches stay as they came, compressed next to plain. */
    @Test
    void relayedFramesInsertAsOneStream() {
        List<DatapointFrame> frames = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            DatapointFrameWriter w = DatapointFrameWriter.forType(DatapointValueType.FLOAT32).series(800 + i, "m" + i);
            for (int r = 0; r < 250; r++) {
                w.addFloat32(800 + i, r * 1000L, i * 1000 + r);
            }
            DatapointFrame inBand = DatapointFrame.parseAll(w.build(ZSTD), ZSTD).get(0);
            frames.add(i % 2 == 0 ? inBand : wholePayloadFrame(inBand));
        }
        insert(DatapointValueType.FLOAT32, frames);
        List<GenericRecord> count = client.queryAll(
                "SELECT count() AS c, sum(value) AS s FROM datapoints_float32 WHERE timeseries_id BETWEEN 800 AND 803");
        assertEquals(1000, count.get(0).getLong("c"));
        double expected = 0;
        for (int i = 0; i < 4; i++) for (int r = 0; r < 250; r++) expected += i * 1000 + r;
        assertEquals(expected, count.get(0).getDouble("s"), 0.5);
    }
}
