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
import ai.intellistream.datahub.jpa.domains.TimeseriesEntity;
import ai.intellistream.datahub.jpa.domains.TimeseriesValueType;
import ai.intellistream.datahub.models.forms.RetrieveFilter;
import ai.intellistream.datahub.services.ValkeyService;
import ai.intellistream.datahub.tenant.Tenant;
import ai.intellistream.datahub.tenant.TenantConfigService;
import ai.intellistream.datahub.testsupport.SharedClickHouse;
import com.clickhouse.client.api.Client;
import com.clickhouse.client.api.ClientConfigProperties;
import com.clickhouse.client.api.query.QueryResponse;
import com.clickhouse.client.api.query.QuerySettings;
import com.clickhouse.data.ClickHouseFormat;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Where a binary read's time goes, stage by stage, against a real ClickHouse holding one float
 * series of four million points in forty parts (what streaming inserts leave behind) and one text
 * series of a million: the query alone with and without {@code FINAL}, over the pool's HTTP LZ4
 * transport, with native transport compression, uncompressed, and with the Arrow buffers
 * compressed in-band as the read asks for; then the relay into frames, which is the production
 * path and compresses nothing; then what a reader pays to decode them; and an Arrow insert of a
 * million points over the pool's transport and uncompressed. Prints a table; run with
 * {@code ./gradlew :datahub-infra:performanceTest --tests "*ClickHouseArrowReadPerformanceIT"}.
 */
@Tag("integration")
@Tag("performance")
class ClickHouseArrowReadPerformanceIT {

    static final long FLOAT_ID = 1;
    static final long TEXT_ID = 2;
    static final int FLOAT_ROWS = 4_000_000;
    static final int TEXT_ROWS = 1_000_000;
    static final int PARTS = 40;
    static final int TINY_SERIES = 200;
    static final int TINY_ROWS = 100;
    static final long T0_SEC = 1_700_000_000L;
    static final int INSERT_ROWS = 1_000_000;

    static Client client;
    static Client plainClient;
    static ClickHouseDatapointService service;

    @BeforeAll
    static void setUp() {
        // The pool's transport, from the shared builder, and no compression at all.
        client = SharedClickHouse.clientBuilder("arrow_read_perf")
                .serverSetting("max_threads", "8")
                .serverSetting("session_timezone", "UTC")
                .build();
        plainClient = SharedClickHouse.clientBuilder("arrow_read_perf")
                .compressClientRequest(false)
                .compressServerResponse(false)
                .serverSetting("max_threads", "8")
                .serverSetting("session_timezone", "UTC")
                .build();
        String engine = " ENGINE = ReplacingMergeTree ORDER BY (timeseries_id, timestamp) PRIMARY KEY (timeseries_id, timestamp) PARTITION BY YEAR(timestamp)";
        ddl("CREATE TABLE datapoints_float (timeseries_id Int64 CODEC(DoubleDelta, ZSTD(9)), timestamp DateTime64(3, 'UTC') CODEC(DoubleDelta, ZSTD(9)), "
                + "value Float64 CODEC(Gorilla, ZSTD(9)))" + engine);
        ddl("CREATE TABLE datapoints_text (timeseries_id Int64 CODEC(DoubleDelta, ZSTD(9)), timestamp DateTime64(3, 'UTC') CODEC(DoubleDelta, ZSTD(9)), "
                + "value LowCardinality(String) CODEC(ZSTD(9)))" + engine);
        // One insert per part, as streaming inserts leave them; FINAL merges these at read time.
        int chunk = FLOAT_ROWS / PARTS;
        for (int p = 0; p < PARTS; p++) {
            ddl("INSERT INTO datapoints_float SELECT " + FLOAT_ID + ", toDateTime64(" + T0_SEC + " + number, 3, 'UTC'), sin(number / 100) * 100 "
                    + "FROM numbers(" + (p * chunk) + ", " + chunk + ")");
        }
        int textChunk = TEXT_ROWS / 10;
        for (int p = 0; p < 10; p++) {
            ddl("INSERT INTO datapoints_text SELECT " + TEXT_ID + ", toDateTime64(" + T0_SEC + " + number, 3, 'UTC'), concat('v', toString(number % 1000)) "
                    + "FROM numbers(" + (p * textChunk) + ", " + textChunk + ")");
        }
        ddl("INSERT INTO datapoints_float SELECT 1000 + intDiv(number, " + TINY_ROWS + "), toDateTime64(" + T0_SEC + " + number % " + TINY_ROWS + ", 3, 'UTC'), number "
                + "FROM numbers(" + (TINY_SERIES * TINY_ROWS) + ")");

        TenantConfigService tenantConfigService = mock(TenantConfigService.class);
        ClickHouseClientPool pool = mock(ClickHouseClientPool.class);
        when(tenantConfigService.getConfig(anyString())).thenReturn(new Tenant());
        when(pool.getClient(any(Tenant.class))).thenReturn(client);
        service = new ClickHouseDatapointService(tenantConfigService, mock(ValkeyService.class), pool);
    }

    @AfterAll
    static void tearDown() {
        if (client != null) client.close();
        if (plainClient != null) plainClient.close();
    }

    static void ddl(String sql) {
        SharedClickHouse.execute(client, sql);
    }

    @Test
    void whereTheTimeGoes() throws Exception {
        System.out.printf("%n%-52s %8s %12s %10s%n", "stage", "best ms", "Mrows/s", "MB out");
        report("float: query, pool transport (HTTP LZ4)", FLOAT_ROWS, () -> drain(client, query("datapoints_float", FLOAT_ID, true)));
        report("float: query, native transport compression", FLOAT_ROWS, () -> drainNative(client, query("datapoints_float", FLOAT_ID, true)));
        report("float: query, native, no FINAL", FLOAT_ROWS, () -> drainNative(client, query("datapoints_float", FLOAT_ID, false)));
        report("float: query, no compression at all", FLOAT_ROWS, () -> drain(plainClient, query("datapoints_float", FLOAT_ID, true)));
        report("float: query, Arrow zstd buffers (the read's)", FLOAT_ROWS, () -> drainRead(service, DatapointValueType.FLOAT, FLOAT_ID));
        report("float: query, Arrow lz4 buffers", FLOAT_ROWS, () -> drainArrowCompressed(plainClient, query("datapoints_float", FLOAT_ID, true), "lz4_frame"));
        report("float: + relay into frames (production)", FLOAT_ROWS, () -> frame(service, DatapointValueType.FLOAT, FLOAT_ID));
        report("float: + decode the frames (a reader's cost)", FLOAT_ROWS, () -> decode(service, DatapointValueType.FLOAT, FLOAT_ID, FLOAT_ROWS));
        report("float: Arrow parse here, in-band decoded", FLOAT_ROWS, () -> parse(service, DatapointValueType.FLOAT, FLOAT_ID, FLOAT_ROWS));
        report("text: query, pool transport (HTTP LZ4)", TEXT_ROWS, () -> drain(client, query("datapoints_text", TEXT_ID, true)));
        report("text: query, native transport compression", TEXT_ROWS, () -> drainNative(client, query("datapoints_text", TEXT_ID, true)));
        report("text: query, Arrow zstd buffers (the read's)", TEXT_ROWS, () -> drainRead(service, DatapointValueType.TEXT, TEXT_ID));
        report("text: + relay into frames (production)", TEXT_ROWS, () -> frame(service, DatapointValueType.TEXT, TEXT_ID));
        report("text: + decode the frames (a reader's cost)", TEXT_ROWS, () -> decode(service, DatapointValueType.TEXT, TEXT_ID, TEXT_ROWS));
        report("text: Arrow parse here, in-band decoded", TEXT_ROWS, () -> parse(service, DatapointValueType.TEXT, TEXT_ID, TEXT_ROWS));
        report("200 series of 100 rows, relayed one by one", (long) TINY_SERIES * TINY_ROWS, () -> tiny(service));
        byte[] arrow = arrowInsert(INSERT_ROWS);
        report("insert 1M floats as Arrow, buffers zstd in-band, body as is", INSERT_ROWS, () -> insert(service, arrow, true));
        report("insert 1M floats as Arrow, transport zstd on top of that", INSERT_ROWS, () -> insert(service, arrow, false));
    }

    interface Stage {
        long run() throws Exception;
    }

    static void report(String stage, long rows, Stage run) {
        long best = Long.MAX_VALUE;
        long out = 0;
        for (int i = 0; i < 3; i++) {
            long t0 = System.nanoTime();
            try {
                out = run.run();
            } catch (Exception e) {
                Throwable root = e;
                while (root.getCause() != null) root = root.getCause();
                System.out.printf("%-52s FAILED: %s%n", stage, root.getMessage() == null ? root : root.getMessage().lines().findFirst().orElse(""));
                return;
            }
            best = Math.min(best, System.nanoTime() - t0);
        }
        System.out.printf("%-52s %8d %12.2f %10.1f%n", stage, best / 1_000_000, rows / (best / 1e3), out / 1e6);
    }

    static String query(String table, long id, boolean fin) {
        return "SELECT timeseries_id, toDateTime64(timestamp, 3, 'UTC') AS timestamp, value FROM " + table + (fin ? " FINAL" : "")
                + " WHERE timeseries_id = " + id + " ORDER BY timestamp";
    }

    /** The read's own query, with the Arrow buffers compressed in-band, drained. */
    static long drainRead(ClickHouseDatapointService svc, DatapointValueType type, long id) throws Exception {
        try (InputStream in = svc.openArrowStream("t", series(type, id), window(), 0, FrameLimits.maxRows(type))) {
            byte[] buf = new byte[1 << 16];
            long n = 0;
            int k;
            while ((k = in.read(buf)) > 0) {
                n += k;
            }
            return n;
        }
    }

    /** As {@link #drain}, asking this one query for ClickHouse's native compression instead of HTTP's. */
    static long drainNative(Client c, String sql) throws Exception {
        QuerySettings qs = new QuerySettings().setFormat(ClickHouseFormat.ArrowStream)
                .setOption(ClientConfigProperties.USE_HTTP_COMPRESSION.getKey(), Boolean.FALSE);
        qs.serverSetting("output_format_arrow_compression_method", "none");
        return drain(c, sql, qs);
    }

    /** ClickHouse compressing each Arrow buffer itself, as the IPC format allows and our frames do not. */
    static long drainArrowCompressed(Client c, String sql, String method) throws Exception {
        QuerySettings qs = new QuerySettings().setFormat(ClickHouseFormat.ArrowStream);
        qs.serverSetting("output_format_arrow_compression_method", method);
        return drain(c, sql, qs);
    }

    /** The service's query settings, the bytes read and thrown away. */
    static long drain(Client c, String sql) throws Exception {
        QuerySettings qs = new QuerySettings().setFormat(ClickHouseFormat.ArrowStream);
        qs.serverSetting("output_format_arrow_compression_method", "none");
        return drain(c, sql, qs);
    }

    static long drain(Client c, String sql, QuerySettings qs) throws Exception {
        qs.serverSetting("output_format_arrow_string_as_string", "1");
        qs.serverSetting("output_format_arrow_low_cardinality_as_dictionary", "0");
        qs.serverSetting("max_block_size", "100000");
        try (QueryResponse response = c.query(sql, qs).get(30, TimeUnit.SECONDS)) {
            InputStream in = response.getInputStream();
            byte[] buf = new byte[1 << 16];
            long n = 0;
            int k;
            while ((k = in.read(buf)) > 0) {
                n += k;
            }
            return n;
        }
    }

    static final PayloadCodec READ_CODEC = new ZstdPayloadCodec(1);

    /** Every batch decoded here, buffers decompressed: what the API paid before it relayed. */
    static long parse(ClickHouseDatapointService svc, DatapointValueType type, long id, int expected) throws Exception {
        long rows = 0;
        try (InputStream in = svc.openArrowStream("t", series(type, id), window(), 0, FrameLimits.maxRows(type))) {
            ArrowIpc.StreamReader reader = new ArrowIpc.StreamReader(ArrowSchemaCanon.of(type), in, 0, Integer.MAX_VALUE, FrameLimits.MAX_REQUEST_RAW_BYTES, READ_CODEC);
            for (ArrowIpc.Batch b = reader.next(); b != null; b = reader.next()) {
                rows += b.rows();
            }
        }
        assertEquals(expected, rows);
        return 0;
    }

    static long frame(ClickHouseDatapointService svc, DatapointValueType type, long id) throws Exception {
        long[] out = new long[1];
        OutputStream counting = new OutputStream() {
            @Override
            public void write(int b) {
                out[0]++;
            }

            @Override
            public void write(byte[] b, int off, int len) {
                out[0] += len;
            }
        };
        try (InputStream in = svc.openArrowStream("t", series(type, id), window(), 0, FrameLimits.maxRows(type))) {
            DatapointFrameWriter.relayStream(type, false, id, "s" + id, in, READ_CODEC, counting);
        }
        return out[0];
    }

    /** The frames relayed into memory and read back the way an SDK reads an answer. */
    static long decode(ClickHouseDatapointService svc, DatapointValueType type, long id, int expected) throws Exception {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        try (InputStream in = svc.openArrowStream("t", series(type, id), window(), 0, FrameLimits.maxRows(type))) {
            DatapointFrameWriter.relayStream(type, false, id, "s" + id, in, READ_CODEC, body);
        }
        body.writeBytes(FrameLimits.END_OF_RESPONSE);
        long rows = 0;
        for (DatapointFrame f : DatapointFrame.parseResponse(body.toByteArray(), READ_CODEC)) {
            rows += f.rowCount();
        }
        assertEquals(expected, rows);
        return body.size();
    }

    static long tiny(ClickHouseDatapointService svc) throws Exception {
        long out = 0;
        for (int s = 0; s < TINY_SERIES; s++) {
            out += frame(svc, DatapointValueType.FLOAT, 1000 + s);
        }
        return out;
    }

    /** One Arrow stream of {@code rows} float points in frames of 100 000, relayed as the consumer sends them. */
    static byte[] arrowInsert(int rows) {
        List<DatapointFrame> frames = new ArrayList<>();
        for (int start = 0; start < rows; start += 100_000) {
            DatapointFrameWriter w = DatapointFrameWriter.forType(DatapointValueType.FLOAT).series(0, "s0");
            for (int r = start; r < Math.min(rows, start + 100_000); r++) {
                w.addFloat(0, T0_SEC * 1000 + r, Math.sin(r / 100.0) * 100);
            }
            frames.add(DatapointFrame.parseAll(w.build(new ZstdPayloadCodec(1)), new ZstdPayloadCodec(1)).get(0));
        }
        return FrameMerger.stream(DatapointValueType.FLOAT, frames.stream().map(f -> FrameMerger.batches(f, new ZstdPayloadCodec(1))).toList());
    }

    /** The same rows again each run: a ReplacingMergeTree takes them as a new part at the same cost. */
    static long insert(ClickHouseDatapointService svc, byte[] arrow, boolean compressed) {
        svc.insertArrowStream("t", DatapointValueType.FLOAT, arrow, compressed);
        return arrow.length;
    }

    static RetrieveFilter window() {
        RetrieveFilter f = new RetrieveFilter();
        f.setStart(Instant.ofEpochSecond(T0_SEC - 1).atZone(ZoneOffset.UTC));
        return f;
    }

    static TimeseriesEntity series(DatapointValueType type, long id) {
        TimeseriesEntity ts = new TimeseriesEntity();
        ts.setId(id);
        ts.setExternalId("s" + id);
        ts.setValueType(new TimeseriesValueType(type.id()));
        return ts;
    }
}
