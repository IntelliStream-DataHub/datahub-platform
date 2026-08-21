// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.services;

import ai.intellistream.datahub.api.responses.DataCollection;
import ai.intellistream.datahub.config.ValkeyConnectionProvider;
import ai.intellistream.datahub.jpa.domains.TimeseriesEntity;
import ai.intellistream.datahub.jpa.domains.TimeseriesValueType;
import ai.intellistream.datahub.util.DatapointsResult;
import ai.intellistream.datahub.util.SavedDatapointsStats;
import com.clickhouse.client.api.data_formats.ClickHouseBinaryFormatReader;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.codec.StringCodec;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.json.JsonMapper;

import java.time.ZonedDateTime;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static ai.intellistream.datahub.jpa.domains.TimeseriesValueType.BIGINT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Proves the datapoint chunking hands back a cursor only when there is genuinely more data than one
 * chunk (the one-row read-ahead), and that a handed-back cursor always drains cleanly — i.e. its
 * metadata is written so {@link ValkeyService#fetchDatapointsFromCursor} can page the tail back, and
 * the list empties so the caller stops paging (no never-draining cursor, no dropped tail).
 *
 * <p>Runs against a real Valkey via Testcontainers; needs a container runtime, so run with
 * {@code ./gradlew :datahub-infra:integrationTest} on a host with Docker/Podman.
 */
@Tag("integration")
@Testcontainers
class ValkeyReadAheadIT {

    // Small chunk so scenarios stay tiny; the read-ahead logic is size-agnostic.
    private static final int CHUNK = 3;
    private static final ZonedDateTime FIXED_TS = ZonedDateTime.parse("2026-01-01T00:00:00Z");

    @Container
    static final GenericContainer<?> VALKEY =
            new GenericContainer<>(DockerImageName.parse("valkey/valkey:8-alpine"))
                    .withExposedPorts(6379);

    static RedisClient redisClient;
    static RedisURI redisUri;

    ValkeyService service;
    StatefulRedisConnection<String, String> connection;

    @BeforeAll
    static void startClient() {
        redisUri = RedisURI.builder()
                .withHost(VALKEY.getHost())
                .withPort(VALKEY.getMappedPort(6379))
                .build();
        redisClient = RedisClient.create();
    }

    @AfterAll
    static void stopClient() {
        if (redisClient != null) redisClient.shutdown();
    }

    @BeforeEach
    void setUp() {
        ValkeyConnectionProvider provider = mock(ValkeyConnectionProvider.class);
        // One long-lived connection handed to every call and never closed by the caller — same
        // contract as the real provider, so ValkeyService behaves exactly as in production.
        connection = redisClient.connect(StringCodec.UTF8, redisUri);
        when(provider.connection()).thenReturn(connection);
        service = new ValkeyService(provider, JsonMapper.builder().build());
    }

    @AfterEach
    void tearDown() {
        if (connection != null) connection.close();
    }

    private static TimeseriesEntity bigintNode() {
        TimeseriesEntity node = new TimeseriesEntity();
        node.setId(42L);
        node.setExternalId("ts-read-ahead");
        node.setUnit("C");
        node.setUnitExternalId("celsius");
        node.setValueType(new TimeseriesValueType(BIGINT));
        return node;
    }

    /** A reader that yields {@code rows} BIGINT datapoints, then reports exhausted. */
    private static ClickHouseBinaryFormatReader readerWith(int rows) {
        ClickHouseBinaryFormatReader reader = mock(ClickHouseBinaryFormatReader.class);
        AtomicInteger produced = new AtomicInteger();
        when(reader.hasNext()).thenAnswer(inv -> produced.get() < rows);
        // getZonedDateTime is called once per row, right after next(); advance the row counter here.
        lenient().when(reader.getZonedDateTime("timestamp_g"))
                .thenAnswer(inv -> { produced.incrementAndGet(); return FIXED_TS; });
        lenient().when(reader.getLong("value")).thenAnswer(inv -> (long) produced.get());
        return reader;
    }

    private DatapointsResult save(int rows) throws Exception {
        CompletableFuture<DatapointsResult> future = new CompletableFuture<>();
        SavedDatapointsStats stats = new SavedDatapointsStats(CHUNK, future);
        service.saveDatapoints(bigintNode(), readerWith(rows), stats);
        return future.get(5, TimeUnit.SECONDS);
    }

    /** TTL in seconds straight from Valkey (-1 = key exists WITHOUT a TTL, -2 = key missing). */
    private long ttlOf(String key) {
        try (var connection = redisClient.connect(StringCodec.UTF8, redisUri)) {
            return connection.sync().ttl(key);
        }
    }

    private void assertCursorKeysHaveTtl(String cursorId) {
        // Both cursor keys must carry a TTL from the moment they exist: EXPIRE on a key that doesn't
        // exist yet is a no-op, so a mis-ordered expire/rpush leaves the tail list immortal and it
        // leaks whenever a client abandons the cursor.
        long listTtl = ttlOf(cursorId);
        long metaTtl = ttlOf(cursorId + "-metadata");
        assertTrue(listTtl > 0, "cursor list must have a TTL, ttl=" + listTtl);
        assertTrue(metaTtl > 0, "cursor metadata must have a TTL, ttl=" + metaTtl);
    }

    @Test
    void underOneChunkCompletesInlineWithNoCursor() throws Exception {
        DatapointsResult res = save(CHUNK - 1); // 2 rows

        assertNull(res.getCursorId(), "fewer than a chunk must not hand back a cursor");
        assertEquals(CHUNK - 1, res.getDatapoints().size());
    }

    @Test
    void exactChunkBoundaryCompletesInlineWithNoCursor() throws Exception {
        // The read-ahead: a result that is exactly one chunk must NOT hand back a cursor, so the
        // client is not forced into a second, empty follow-up request.
        DatapointsResult res = save(CHUNK); // 3 rows

        assertNull(res.getCursorId(), "an exact chunk boundary must not hand back a cursor");
        assertEquals(CHUNK, res.getDatapoints().size());
    }

    @Test
    void oneOverChunkHandsBackACursorThatDrains() throws Exception {
        DatapointsResult res = save(CHUNK + 1); // 4 rows: 3 inline + 1 tail

        assertNotNull(res.getCursorId(), "more than a chunk must hand back a cursor");
        assertEquals(CHUNK, res.getDatapoints().size());
        assertEquals(1, service.getListSize(res.getCursorId()), "the tail row waits in Valkey");
        assertCursorKeysHaveTtl(res.getCursorId());

        // The tail pages back (metadata was written), and the list drains so the caller stops paging.
        DataCollection<?> tail = service.fetchDatapointsFromCursor(res.getCursorId(), 100);
        assertEquals(1, tail.getDatapoints().size());
        assertEquals(0, service.getListSize(res.getCursorId()), "cursor drains — no never-ending paging");
    }

    @Test
    void exactTwoChunksHandsBackACursorThatDrains() throws Exception {
        DatapointsResult res = save(2 * CHUNK); // 6 rows: 3 inline + 3 tail

        assertNotNull(res.getCursorId());
        assertEquals(CHUNK, res.getDatapoints().size());
        assertEquals(CHUNK, service.getListSize(res.getCursorId()));
        assertCursorKeysHaveTtl(res.getCursorId());

        DataCollection<?> tail = service.fetchDatapointsFromCursor(res.getCursorId(), 100);
        assertEquals(CHUNK, tail.getDatapoints().size());
        assertEquals(0, service.getListSize(res.getCursorId()), "cursor drains fully in one page");
    }
}
