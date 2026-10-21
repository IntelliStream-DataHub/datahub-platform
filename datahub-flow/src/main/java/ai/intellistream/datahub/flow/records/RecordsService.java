// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.flow.records;

import ai.intellistream.datahub.api.responses.DataWrapper;
import ai.intellistream.datahub.clickhouse.ClickHouseClientPool;
import ai.intellistream.datahub.flow.access.Caller;
import ai.intellistream.datahub.flow.flows.FlowService;
import ai.intellistream.datahub.flow.records.RecordsTable.Position;
import ai.intellistream.datahub.flow.records.RecordsTable.StoredRecord;
import ai.intellistream.datahub.flow.records.RecordsTable.Write;
import ai.intellistream.datahub.flow.store.RunStore.StoredRun;
import ai.intellistream.datahub.flow.runs.RunService;
import ai.intellistream.datahub.flow.web.FlowProblems;
import ai.intellistream.datahub.tenant.TenantConfigService;
import ai.intellistream.datahub.tenant.TenantContext;
import com.clickhouse.client.api.Client;
import com.fasterxml.jackson.annotation.JsonInclude;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Reading what runs recorded: a run's records, and which run last recorded writing a value.
 * Records are read from the tenant's ClickHouse with its read-only user; whether the caller may see
 * them is decided by the run's flow, as for the run itself.
 */
@Service
public class RecordsService {

    static final int DEFAULT_LIMIT = 1000;
    static final int MAX_LIMIT = 10_000;
    /** How many recorded writes of a value the lookup returns: the latest and its history. */

    private final RunService runs;
    private final FlowService flows;
    private final Supplier<Client> clickhouse;
    private final JsonMapper json;
    private final Duration pollInterval;

    @Autowired
    public RecordsService(RunService runs, FlowService flows,
                          TenantConfigService tenants, ClickHouseClientPool pool, JsonMapper json,
                          @Value("${datahub.flow.records.live-poll:PT1S}") Duration pollInterval) {
        this(runs, flows,
                () -> pool.getReadOnlyClient(tenants.getConfig(TenantContext.getTenantId())), json,
                pollInterval);
    }

    RecordsService(RunService runs, FlowService flows,
                   Supplier<Client> clickhouse, JsonMapper json, Duration pollInterval) {
        this.runs = runs;
        this.flows = flows;
        this.clickhouse = clickhouse;
        this.json = json;
        this.pollInterval = pollInterval;
    }

    /** A run's records in the order they happened: by attempt, batch and row. */
    public DataWrapper<StoredRecord> records(UUID runId, String cursor, Integer limit) {
        runs.readable(runId);
        int n = limit == null ? DEFAULT_LIMIT : limit;
        if (n < 1 || n > MAX_LIMIT) {
            throw FlowProblems.badRequest("limit is between 1 and " + MAX_LIMIT + ".");
        }
        List<StoredRecord> page = RecordsTable.list(clickhouse.get(), runId, decode(cursor), n);
        DataWrapper<StoredRecord> out = new DataWrapper<>();
        out.setItems(page);
        if (page.size() == n) {
            StoredRecord last = page.getLast();
            out.setNextCursor(encode(new Position(last.attempt(), last.sequence(), last.ordinal())));
        }
        return out;
    }

    /**
     * The run's live view ({@link RunEventStream}), for a caller who may see the run. It runs on a
     * thread of its own, after this request has returned its headers, under the caller's tenant.
     */
    public StreamingResponseBody events(UUID runId) {
        StoredRun run = runs.readable(runId);
        String tenantId = TenantContext.getTenantId();
        RunEventStream stream = new RunEventStream(runId, () -> runs.lookup(runId),
                after -> RecordsTable.list(clickhouse.get(), runId, after, RunEventStream.PAGE), json,
                pollInterval, run.timeout().plus(Duration.ofMinutes(5)));
        return out -> TenantContext.runWith(tenantId, () -> {
            try {
                stream.write(out);
            } catch (IOException e) {
                // The browser went away; there is no one left to tell.
                throw new UncheckedIOException(e);
            }
        });
    }

    /**
     * Which run last recorded writing {@code timeseries} at {@code at}: the latest write whose window
     * covers the instant, of a flow the caller may read, as the run and its flow, to link to. It
     * answers what was written, not what ClickHouse holds now: datapoints land through Pulsar and
     * several consumers, so two writes close together can land in either order. No such write is an
     * answer too, with neither.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Lookup(UUID run, String flow) {
    }

    public Lookup lookup(String timeseries, Instant at) {
        Caller caller = flows.requireRead();
        if (timeseries == null || timeseries.isBlank() || at == null) {
            throw FlowProblems.badRequest("timeseries and at are required.");
        }
        Caller.DataSets readable = caller.admin() ? Caller.DataSets.ALL : caller.read();
        return RecordsTable.latestWrite(clickhouse.get(), timeseries, at, readable.all() ? null : readable.ids())
                .map(w -> new Lookup(w.runId(), w.flowExternalId()))
                .orElse(new Lookup(null, null));
    }

    /** An opaque cursor for a page of records: where the last page ended. */
    static String encode(Position p) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                (p.attempt() + ":" + p.sequence() + ":" + p.ordinal()).getBytes(StandardCharsets.UTF_8));
    }

    static Position decode(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        try {
            String[] parts = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8)
                    .split(":");
            return new Position(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]),
                    Integer.parseInt(parts[2]));
        } catch (RuntimeException e) {
            throw FlowProblems.badRequest("cursor is not one this service returned.");
        }
    }
}
