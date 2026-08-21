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
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Proves a datapoint written as absolute epoch millis is found by a time filter expressed in a non-UTC
 * offset — the case that {@code TimestampDeserializer} accepts ("ISO strings keep their own zone/offset")
 * but that the query layer used to mishandle, because the DateTime64 formatter has no offset field and so
 * emitted the caller's wall clock verbatim.
 *
 * <p>Two conditions are deliberately hostile and mirror production rather than the happy path:
 * <ul>
 *   <li>the server is configured to America/New_York, so anything relying on the server's zone breaks;</li>
 *   <li>the table uses bare {@code DateTime64(3)} with no timezone, as every <em>existing</em> tenant table
 *       does — clickhouse.sql now declares {@code DateTime64(3, 'UTC')}, but that only helps tables created
 *       from it afterwards. The {@code session_timezone=UTC} setting this test pins is what makes the
 *       already-deployed tables correct without a migration.</li>
 * </ul>
 */
@Tag("integration")
@Testcontainers
class ClickHouseTimezoneFilterIT {

    /** A server zone that is neither UTC nor the caller's, so a dropped offset cannot coincidentally pass. */
    private static final String SERVER_TZ = "America/New_York";

    /** 2026-07-17T12:00:00Z — the instant under test, stored as epoch millis. */
    private static final long TS_MILLIS = ZonedDateTime.parse("2026-07-17T12:00:00Z").toInstant().toEpochMilli();

    private static final long TS_ID = 1L;

    // The server zone must come from ClickHouse's own <timezone> config — the TZ env var is silently ignored,
    // which is why serverIsNonUtcButSessionIsPinnedToUtc() guards the premise rather than trusting it.
    @Container
    static final ClickHouseContainer CLICKHOUSE =
            new ClickHouseContainer(DockerImageName.parse("clickhouse/clickhouse-server:26.5"))
                    .withUsername("tester")
                    .withPassword("test")
                    .withCopyToContainer(
                            Transferable.of("<clickhouse><timezone>" + SERVER_TZ + "</timezone></clickhouse>"),
                            "/etc/clickhouse-server/config.d/timezone.xml")
                    .waitingFor(Wait.forHttp("/ping").forPort(8123).forStatusCode(200));

    static Client client;

    @BeforeAll
    static void setUp() throws Exception {
        client = new Client.Builder()
                .addEndpoint("http://" + CLICKHOUSE.getHost() + ":" + CLICKHOUSE.getMappedPort(8123))
                .setUsername(CLICKHOUSE.getUsername())
                .setPassword(CLICKHOUSE.getPassword())
                .setDefaultDatabase("default")
                // Mirrors ClickHouseClientPool#build. Without this the bare DateTime64(3) below is parsed
                // in SERVER_TZ and every assertion here fails.
                .serverSetting("session_timezone", "UTC")
                .build();

        // Bare DateTime64(3) on purpose: this is the legacy/deployed column shape, not the new DDL.
        SharedClickHouse.execute(client, "CREATE TABLE datapoints_bigint (timeseries_id Int64, timestamp DateTime64(3), value Int64)"
                + " ENGINE=MergeTree ORDER BY (timeseries_id, timestamp)");

        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(bos);
        dos.writeLong(Long.reverseBytes(TS_ID));
        dos.writeLong(Long.reverseBytes(TS_MILLIS)); // absolute instant, as the production insert path writes it
        dos.writeLong(Long.reverseBytes(42L));
        dos.flush();
        try (InsertResponse ignored = client.insert("datapoints_bigint",
                new ByteArrayInputStream(bos.toByteArray()), ClickHouseFormat.RowBinary, new InsertSettings()).get()) {
            // row inserted
        }
    }

    @AfterAll
    static void tearDown() {
        if (client != null) client.close();
    }

    /** Runs the production filter shape with bounds bound exactly as ClickHouseDatapointService binds them. */
    private static long countInWindow(String startIso, String endIso) {
        Map<String, Object> params = new HashMap<>();
        params.put("timeseriesId", TS_ID);
        params.put("startTime", ClickHouseService.toChDateTime(ZonedDateTime.parse(startIso)));
        params.put("endTime", ClickHouseService.toChDateTime(ZonedDateTime.parse(endIso)));
        List<GenericRecord> rows = client.queryAll(
                "SELECT count() AS c FROM datapoints_bigint WHERE timeseries_id = {timeseriesId:Int64}"
                        + " AND timestamp >= {startTime:DateTime64(3)} AND timestamp < {endTime:DateTime64(3)}",
                params);
        return rows.get(0).getLong("c");
    }

    /**
     * The regression. 11:00–13:00+02:00 is 09:00–11:00 UTC and must NOT match the 12:00Z point; before the
     * fix the offset was dropped, so the server saw 11:00–13:00 and returned it.
     */
    @Test
    void windowExpressedInNonUtcOffsetExcludesPointOutsideTheRealInstant() {
        assertEquals(0, countInWindow("2026-07-17T11:00:00+02:00", "2026-07-17T13:00:00+02:00"),
                "11:00-13:00+02:00 is 09:00-11:00Z and must not contain the 12:00Z datapoint");
    }

    /** The mirror: 13:00–15:00+02:00 is 11:00–13:00 UTC and must match. */
    @Test
    void windowExpressedInNonUtcOffsetIncludesPointInsideTheRealInstant() {
        assertEquals(1, countInWindow("2026-07-17T13:00:00+02:00", "2026-07-17T15:00:00+02:00"),
                "13:00-15:00+02:00 is 11:00-13:00Z and must contain the 12:00Z datapoint");
    }

    /** Offsets naming the same instant must agree, whatever zone the caller used to express them. */
    @Test
    void equivalentWindowsInDifferentOffsetsAgree() {
        long utc = countInWindow("2026-07-17T11:00:00Z", "2026-07-17T13:00:00Z");
        long plusTwo = countInWindow("2026-07-17T13:00:00+02:00", "2026-07-17T15:00:00+02:00");
        long minusFive = countInWindow("2026-07-17T06:00:00-05:00", "2026-07-17T08:00:00-05:00");
        assertEquals(1, utc);
        assertEquals(utc, plusTwo, "+02:00 window over the same instants must match the Z window");
        assertEquals(utc, minusFive, "-05:00 window over the same instants must match the Z window");
    }

    /** The value read back must be the instant that was written, not the server zone's reading of it. */
    @Test
    void timestampReadsBackAsTheInstantWritten() {
        List<GenericRecord> rows = client.queryAll(
                "SELECT timestamp FROM datapoints_bigint WHERE timeseries_id = " + TS_ID);
        ZonedDateTime read = rows.get(0).getZonedDateTime("timestamp");
        assertEquals(Instant.ofEpochMilli(TS_MILLIS), read.toInstant());
    }

    /**
     * Guards the premise, and pins the mechanism. {@code serverTimeZone()} is the container's configured
     * zone; {@code timezone()} is what the session actually resolves DateTime64 text against. The pair must
     * differ — a non-UTC server overridden to UTC for the session — or the other tests here prove nothing.
     */
    @Test
    void serverIsNonUtcButSessionIsPinnedToUtc() {
        GenericRecord row = client.queryAll("SELECT serverTimeZone() AS server, timezone() AS session").get(0);
        assertEquals(SERVER_TZ, row.getString("server"),
                "test premise: the container must not be running on UTC");
        assertEquals("UTC", row.getString("session"),
                "session_timezone must override the server zone; this is what makes legacy bare "
                        + "DateTime64(3) columns correct without a DDL migration");
    }
}
