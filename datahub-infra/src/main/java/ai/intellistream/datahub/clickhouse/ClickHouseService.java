// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.clickhouse;

import ai.intellistream.datahub.models.events.*;
import ai.intellistream.datahub.services.ValkeyService;
import ai.intellistream.datahub.tenant.Tenant;
import ai.intellistream.datahub.tenant.TenantConfigService;
import ai.intellistream.datahub.tenant.TenantContext;
import com.clickhouse.client.api.Client;
import com.clickhouse.client.api.insert.InsertSettings;
import com.clickhouse.data.*;
import com.clickhouse.data.value.*;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.hc.core5.http.HttpHeaders;
import org.springframework.stereotype.Component;

import java.io.*;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Component
@Slf4j
@AllArgsConstructor
public class ClickHouseService {

    private final TenantConfigService tenantConfigService;
    protected final ValkeyService valkeyService;
    private final ClickHouseClientPool clickHouseClientPool;

    private static final DateTimeFormatter CH_DT_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    public final static int BUFFER_SIZE = 65536;

    private static final String tableEngine = "ReplacingMergeTree";

    public static final int TIMESTAMP_SCALE = 3;
    public static final ClickHouseFormat ROWBIN = ClickHouseFormat.RowBinary;

    /**
     * Renders a {@link ZonedDateTime} as a ClickHouse DateTime64(3) literal, normalising to UTC first.
     *
     * <p>{@link #CH_DT_FORMAT} has no offset field, so formatting a zoned value directly emits that zone's
     * wall clock and silently drops the offset — a client filtering from {@code 14:00+02:00} would query
     * from 14:00 UTC instead of 12:00. Callers must route every DateTime64 bind parameter through here.
     * The server reads the resulting text as UTC because {@code ClickHouseClientPool} pins
     * {@code session_timezone=UTC}; datapoint/event rows themselves are stored as absolute epoch millis.
     */
    protected static String toChDateTime(ZonedDateTime time) {
        return time.withZoneSameInstant(ZoneOffset.UTC).format(CH_DT_FORMAT);
    }

    public Client getClickhouseClient(){
        return getClickhouseClient(TenantContext.getTenantId());
    }

    public Client getClickhouseClient(String tenantId){
        return clickHouseClientPool.getClient(this.tenantConfigService.getConfig(tenantId));
    }

    public Client getClickhouseClient(Tenant t){
        return clickHouseClientPool.getClient(t);
    }

    /**
     * Evict and close the cached ClickHouse client for a tenant — call when a tenant is removed or
     * its ClickHouse credentials are rotated. A new client is built lazily on the next access.
     */
    public void invalidateClickhouseClient(String tenantId){
        clickHouseClientPool.invalidate(tenantId);
    }

    protected InsertSettings getSettings(){
        return new InsertSettings()
                // 16KB buffer for efficient copying from your Pipe to the Network
                .setInputStreamCopyBufferSize(BUFFER_SIZE)
                // Crucial: Tell server the stream is ALREADY compressed
                .httpHeader(HttpHeaders.CONTENT_ENCODING, "zstd");
    }

    protected String getDatabaseName() {
        return TenantContext.getTenantId();
    }

    /**
     * Close a resource without propagating exceptions. Used in cleanup paths around the
     * PipedOutputStream/PipedInputStream pairs we hand to the ClickHouse client — closing the
     * write end is what unblocks a reader thread that's waiting for data, so a failed or
     * timed-out insert must force the pipe shut even if the normal close path didn't run.
     */
    protected static void closeQuietly(Closeable c) {
        if (c == null) return;
        try {
            c.close();
        } catch (IOException e) {
            log.debug("Ignored close failure on {}: {}", c.getClass().getSimpleName(), e.getMessage());
        }
    }

}
