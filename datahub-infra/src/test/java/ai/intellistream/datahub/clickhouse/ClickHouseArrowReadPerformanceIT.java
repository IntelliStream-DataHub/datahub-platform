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
 * transport, with the native compression the read asks for, and uncompressed; then the Arrow
 * parse, then the framing with zstd; and an Arrow insert of a million points over the pool's
 * transport and uncompressed. Prints a table; run with
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
    static ClickHouseDatapointService plainService;

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
        ClickHouseClientPool plainPool = mock(ClickHouseClientPool.class);
        when(plainPool.getClient(any(Tenant.class))).thenReturn(plainClient);
        plainService = new ClickHouseDatapointService(tenantConfigService, mock(ValkeyService.class), plainPool);
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
        report("float: query, native compression (the read's)", FLOAT_ROWS, () -> drainNative(client, query("datapoints_float", FLOAT_ID, true)));
        report("float: query, native, no FINAL", FLOAT_ROWS, () -> drainNative(client, query("datapoints_float", FLOAT_ID, false)));
        report("float: query, no compression", FLOAT_ROWS, () -> drain(plainClient, query("datapoints_float", FLOAT_ID, true)));
        report("float: + Arrow parse (StreamReader)", FLOAT_ROWS, () -> parse(service, DatapointValueType.FLOAT, FLOAT_ID, FLOAT_ROWS));
        report("float: + frames, zstd 1 (production)", FLOAT_ROWS, () -> frame(service, DatapointValueType.FLOAT, FLOAT_ID, new ZstdPayloadCodec(1)));
        report("float: + frames, zstd 3", FLOAT_ROWS, () -> frame(service, DatapointValueType.FLOAT, FLOAT_ID, new ZstdPayloadCodec(3)));
        report("text: query, pool transport (HTTP LZ4)", TEXT_ROWS, () -> drain(client, query("datapoints_text", TEXT_ID, true)));
        report("text: query, native compression (the read's)", TEXT_ROWS, () -> drainNative(client, query("datapoints_text", TEXT_ID, true)));
        report("text: + Arrow parse (StreamReader)", TEXT_ROWS, () -> parse(service, DatapointValueType.TEXT, TEXT_ID, TEXT_ROWS));
        report("text: + frames, zstd 1 (production)", TEXT_ROWS, () -> frame(service, DatapointValueType.TEXT, TEXT_ID, new ZstdPayloadCodec(1)));
        report("200 series of 100 rows, framed one by one", (long) TINY_SERIES * TINY_ROWS, () -> tiny(service));
        report("200 series of 100 rows, no compression", (long) TINY_SERIES * TINY_ROWS, () -> tiny(plainService));
        byte[] arrow = arrowInsert(INSERT_ROWS);
        report("insert 1M floats as Arrow, pool transport", INSERT_ROWS, () -> insert(service, arrow));
        report("insert 1M floats as Arrow, no compression", INSERT_ROWS, () -> insert(plainService, arrow));
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

    /** As {@link #drain}, asking this one query for ClickHouse's native compression instead of HTTP's. */
    static long drainNative(Client c, String sql) throws Exception {
        QuerySettings qs = new QuerySettings().setFormat(ClickHouseFormat.ArrowStream)
                .setOption(ClientConfigProperties.USE_HTTP_COMPRESSION.getKey(), Boolean.FALSE);
        return drain(c, sql, qs);
    }

    /** The service's query settings, the bytes read and thrown away. */
    static long drain(Client c, String sql) throws Exception {
        return drain(c, sql, new QuerySettings().setFormat(ClickHouseFormat.ArrowStream));
    }

    static long drain(Client c, String sql, QuerySettings qs) throws Exception {
        qs.serverSetting("output_format_arrow_string_as_string", "1");
        qs.serverSetting("output_format_arrow_low_cardinality_as_dictionary", "0");
        qs.serverSetting("output_format_arrow_compression_method", "none");
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

    static long parse(ClickHouseDatapointService svc, DatapointValueType type, long id, int expected) throws Exception {
        long rows = 0;
        try (InputStream in = svc.openArrowStream("t", series(type, id), window(), 0, FrameLimits.maxRows(type))) {
            ArrowIpc.StreamReader reader = new ArrowIpc.StreamReader(ArrowSchemaCanon.of(type), in, 0, Integer.MAX_VALUE, FrameLimits.MAX_REQUEST_RAW_BYTES);
            for (ArrowIpc.Batch b = reader.next(); b != null; b = reader.next()) {
                rows += b.rows();
            }
        }
        assertEquals(expected, rows);
        return 0;
    }

    static long frame(ClickHouseDatapointService svc, DatapointValueType type, long id, PayloadCodec codec) throws Exception {
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
            DatapointFrameWriter.wrapStream(type, false, id, "s" + id, in, codec, counting);
        }
        return out[0];
    }

    static long tiny(ClickHouseDatapointService svc) throws Exception {
        long out = 0;
        for (int s = 0; s < TINY_SERIES; s++) {
            out += frame(svc, DatapointValueType.FLOAT, 1000 + s, new ZstdPayloadCodec(1));
        }
        return out;
    }

    /** One Arrow stream of {@code rows} float points, as the consumer sends a merged block. */
    static byte[] arrowInsert(int rows) {
        List<DatapointFrame> frames = new ArrayList<>();
        for (int start = 0; start < rows; start += 100_000) {
            DatapointFrameWriter w = DatapointFrameWriter.forType(DatapointValueType.FLOAT).series(0, "s0");
            for (int r = start; r < Math.min(rows, start + 100_000); r++) {
                w.addFloat(0, T0_SEC * 1000 + r, Math.sin(r / 100.0) * 100);
            }
            frames.add(DatapointFrame.parseAll(w.build(new ZstdPayloadCodec(1)), new ZstdPayloadCodec(1)).get(0));
        }
        return FrameMerger.merge(DatapointValueType.FLOAT, frames, rows);
    }

    /** The same rows again each run: a ReplacingMergeTree takes them as a new part at the same cost. */
    static long insert(ClickHouseDatapointService svc, byte[] arrow) {
        svc.insertArrowStream("t", DatapointValueType.FLOAT, arrow);
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
