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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
    static final int LOOKUP_LIMIT = 50;

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
     * One recorded write of a series, with what the run that made it ran with.
     *
     * @param parameters        every parameter as the run resolved it
     * @param processorVersions every processor type in its flow with the version that ran
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RecordedWrite(UUID run, int attempt, String flow, int flowVersion, String processor,
                                Instant time, Instant windowStart, Instant windowEnd, String runStatus,
                                JsonNode parameters, JsonNode processorVersions) {
    }

    /**
     * Which run produced a value: the latest recorded write of {@code timeseries} whose window
     * covers {@code at}, and the earlier ones as history. It sees only writes made by flows the
     * caller may read, and answers what was written, not what ClickHouse holds now: datapoints land
     * through Pulsar and several consumers, so two writes close together can land in either order.
     */
    public record Lookup(RecordedWrite latest, List<RecordedWrite> history) {
    }

    public Lookup lookup(String timeseries, Instant at) {
        Caller caller = flows.requireRead();
        if (timeseries == null || timeseries.isBlank() || at == null) {
            throw FlowProblems.badRequest("timeseries and at are required.");
        }
        List<RecordedWrite> visible = new ArrayList<>();
        List<Write> writes = RecordsTable.writes(clickhouse.get(), timeseries, at, LOOKUP_LIMIT);
        Map<UUID, StoredRun> found = runs.lookup(writes.stream().map(Write::runId).toList());
        for (Write w : writes) {
            // A run deleted by retention, or of a flow the caller may not see, is left out.
            Optional<StoredRun> run = Optional.ofNullable(found.get(w.runId()))
                    .filter(r -> r.dataSetId() != null && caller.canRead(r.dataSetId()));
            run.ifPresent(r -> visible.add(new RecordedWrite(w.runId(), w.attempt(), w.flowExternalId(),
                    w.flowVersion(), w.processor(), w.time(), w.windowStart(), w.windowEnd(), r.status(),
                    tree(r.parameters()), tree(r.processorVersions()))));
        }
        if (visible.isEmpty()) {
            throw FlowProblems.notFound("No flow you can see recorded writing " + timeseries + " at "
                    + at + ".");
        }
        return new Lookup(visible.getFirst(), List.copyOf(visible.subList(1, visible.size())));
    }

    private JsonNode tree(String text) {
        return text == null ? null : json.readTree(text);
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
