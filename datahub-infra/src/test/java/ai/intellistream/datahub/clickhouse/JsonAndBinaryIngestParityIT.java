// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.clickhouse;

import ai.intellistream.datahub.api.binary.DatapointFrame;
import ai.intellistream.datahub.api.binary.DatapointFrameWriter;
import ai.intellistream.datahub.api.binary.DatapointValueType;
import ai.intellistream.datahub.api.binary.FrameLimits;
import ai.intellistream.datahub.api.binary.FrameMerger;
import ai.intellistream.datahub.api.binary.PayloadCodec;
import ai.intellistream.datahub.api.binary.ZstdPayloadCodec;
import ai.intellistream.datahub.api.responses.DataCollectionString;
import ai.intellistream.datahub.api.responses.DataWrapperMessage;
import ai.intellistream.datahub.api.responses.DatapointString;
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

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The same points, sent as JSON and as binary frames, must be stored identically: a reader cannot
 * tell which path wrote a row. Each path runs its production conversion into a real ClickHouse,
 * JSON through {@link DatapointBinaryConverter} to RowBinary ({@code insertBinaryDatapoints}), binary
 * through the frame the SDK builds ({@link DatapointFrameWriter}) relayed as Arrow
 * ({@code insertArrowStream}). The two paths are compared with each other, not with the input:
 * ClickHouse renders a number its own way (a float zero reads back as {@code 0}), so comparing with
 * the literal would test its formatter rather than the contract.
 *
 * <p>Needs a container runtime; run with {@code ./gradlew :datahub-infra:integrationTest}.
 */
@Tag("integration")
class JsonAndBinaryIngestParityIT {

    private static final PayloadCodec ZSTD = new ZstdPayloadCodec(3);
    private static final long START = Instant.parse("2026-01-01T00:00:00Z").toEpochMilli();

    static Client client;
    static ClickHouseDatapointService service;

    @BeforeAll
    static void setUp() {
        client = SharedClickHouse.newClient("ingest_parity_it");
        for (DatapointValueType type : DatapointValueType.values()) {
            String value = switch (type) {
                case BIGINT -> "value Int64";
                case FLOAT -> "value Float64";
                case FLOAT32 -> "value Float32";
                case NUMERIC -> "value Decimal64(6)";
                case DECIMAL32 -> "value Decimal32(4)";
                case TEXT -> "value LowCardinality(String)";
                case MIXED -> "value_numeric Nullable(Float64), value_text LowCardinality(Nullable(String))";
            };
            SharedClickHouse.execute(client, "CREATE TABLE " + type.tableName() + " (timeseries_id Int64, "
                    + "timestamp DateTime64(3, 'UTC'), " + value + ") ENGINE=ReplacingMergeTree "
                    + "ORDER BY (timeseries_id, timestamp)");
        }
        TenantConfigService tenants = mock(TenantConfigService.class);
        ClickHouseClientPool pool = mock(ClickHouseClientPool.class);
        when(tenants.getConfig(anyString())).thenReturn(new Tenant());
        when(pool.getClient(any(Tenant.class))).thenReturn(client);
        service = new ClickHouseDatapointService(tenants, mock(ValkeyService.class), pool);
    }

    @AfterAll
    static void tearDown() {
        if (client != null) client.close();
    }

    @Test
    void manyPointsSentBothWaysStoreTheSameRows() {
        List<String> values = new ArrayList<>();
        for (int i = 0; i < 25_000; i++) {
            values.add(Float.toString(i % 1024 + 0.25f));   // exact in float32
        }
        viaJson(DatapointValueType.FLOAT32, 1, values);
        viaBinary(DatapointValueType.FLOAT32, 2, values);

        // Not just the same count, the same rows: a checksum over the whole series catches a value
        // one path's encoding mangled, which a count never would.
        assertThat(count(DatapointValueType.FLOAT32, 2)).isEqualTo(25_000);
        assertThat(digest(DatapointValueType.FLOAT32, 2))
                .as("the binary path stored different rows than the JSON path for identical input")
                .isEqualTo(digest(DatapointValueType.FLOAT32, 1));
    }

    @Test
    void everyValueTypeStoresTheSameThroughBothPaths() {
        record Case(DatapointValueType type, List<String> values) {
        }
        List<Case> cases = List.of(
                new Case(DatapointValueType.BIGINT, List.of("-9007199254740993", "0", "42")),
                new Case(DatapointValueType.FLOAT, List.of("-1.5", "0.0", "179.9514040223")),
                new Case(DatapointValueType.FLOAT32, List.of("-1.5", "0.0", "3.25")),
                new Case(DatapointValueType.NUMERIC, List.of("-1.234567", "0.000000", "999.999999")),
                new Case(DatapointValueType.DECIMAL32, List.of("-9999.9999", "0.0000", "1234.5678")),
                new Case(DatapointValueType.TEXT, List.of("running", "FAULT", "stopped")),
                new Case(DatapointValueType.MIXED, List.of("23.5", "FAULT", "24.5")));

        long id = 100;
        for (Case c : cases) {
            long json = id++;
            long binary = id++;
            viaJson(c.type(), json, c.values());
            viaBinary(c.type(), binary, c.values());

            assertThat(stored(c.type(), binary))
                    .as("value type %s differs between the binary and JSON paths", c.type())
                    .isEqualTo(stored(c.type(), json))
                    .hasSize(c.values().size());
        }
    }

    /** As the api publishes a JSON insert and the consumer writes it: strings to RowBinary. */
    private static void viaJson(DatapointValueType type, long id, List<String> values) {
        List<DatapointString> points = new ArrayList<>();
        for (int i = 0; i < values.size(); i++) {
            points.add(new DatapointString(Long.toString(START + i * 1000L), values.get(i)));
        }
        DataCollectionString collection = new DataCollectionString();
        collection.setId(id);
        collection.setExternalId("json_" + id);
        collection.setValueType(type.tableSuffix());
        collection.setDatapoints(points);
        service.insertBinaryDatapoints(DatapointBinaryConverter.toBinary(
                new DataWrapperMessage(null, null, List.of(collection), "t")).getItems(), "t");
    }

    /** As the SDK builds a frame and the consumer relays its batches to ClickHouse. */
    private static void viaBinary(DatapointValueType type, long id, List<String> values) {
        DatapointFrameWriter writer = DatapointFrameWriter.forType(type).series(id, "binary_" + id);
        for (int i = 0; i < values.size(); i++) {
            writer.add(id, START + i * 1000L, values.get(i));
        }
        DatapointFrame frame = DatapointFrame.parseAll(writer.build(ZSTD), ZSTD).getFirst();
        service.insertArrowStream("t", type, FrameMerger.stream(type, List.of(FrameMerger.batches(frame, ZSTD))),
                frame.compression() == FrameLimits.COMPRESSION_ARROW_ZSTD);
    }

    private static String valueExpr(DatapointValueType type) {
        return type == DatapointValueType.MIXED ? "coalesce(toString(value_numeric), value_text)" : "toString(value)";
    }

    private static long count(DatapointValueType type, long id) {
        return client.queryAll("SELECT count() AS c FROM " + type.tableName() + " FINAL WHERE timeseries_id = " + id)
                .getFirst().getLong("c");
    }

    private static String digest(DatapointValueType type, long id) {
        return client.queryAll("SELECT toString(groupBitXor(cityHash64(timestamp, " + valueExpr(type) + "))) AS d FROM "
                + type.tableName() + " FINAL WHERE timeseries_id = " + id).getFirst().getString("d");
    }

    private static List<String> stored(DatapointValueType type, long id) {
        return client.queryAll("SELECT toUnixTimestamp64Milli(timestamp) AS ts, " + valueExpr(type) + " AS v FROM "
                        + type.tableName() + " FINAL WHERE timeseries_id = " + id + " ORDER BY timestamp").stream()
                .map((GenericRecord r) -> r.getLong("ts") + "=" + r.getString("v")).toList();
    }
}
