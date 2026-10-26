// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.flow.store;

import ai.intellistream.datahub.tenant.TenantContext;
import io.lettuce.core.api.sync.RedisCommands;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * What an agent run said and did, row by row, as it happens: a list per run attempt in the tenant's
 * KVRocks ({@code flow:transcript:<run>:<attempt>}, see RUN_STORE.md), appended one row at a time
 * and read from any position, so the console can follow a run that is still going and the second
 * attempt of a run can see what the first did. A finished run's lists go to ClickHouse with the run
 * ({@link RunArchiver}, {@link RunHistory#insertTranscript}) and expire here with its hash.
 *
 * <p>A row's position in the list is its {@code seq}; nothing is stored for it. One command per row,
 * no transaction: a row that lands after a crash is still a true row of what happened.
 */
@Component
public class AgentTranscripts {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /**
     * One row. {@code kind} is one of {@code system}, {@code user}, {@code assistant},
     * {@code tool_call}, {@code tool_result}, {@code usage}, {@code note} and {@code truncated};
     * {@code payload} is what the kind carries, as JSON.
     */
    public record Row(long seq, Instant time, String kind, JsonNode payload) {
    }

    private final TenantKvrocks kvrocks;

    public AgentTranscripts(TenantKvrocks kvrocks) {
        this.kvrocks = kvrocks;
    }

    /** Appends a row to the run attempt's transcript; how many bytes it took. */
    public int append(UUID runId, int attempt, Instant time, String kind, Object payload) {
        ObjectNode row = JSON.createObjectNode();
        row.put("time", time.toString());
        row.put("kind", kind);
        row.set("payload", JSON.valueToTree(payload));
        String text = JSON.writeValueAsString(row);
        commands().rpush(key(runId, attempt), text);
        return text.length();
    }

    /** The rows after {@code after} ({@code -1} for all), in order. */
    public List<Row> read(UUID runId, int attempt, long after) {
        List<String> texts = commands().lrange(key(runId, attempt), after + 1, -1);
        List<Row> rows = new ArrayList<>(texts.size());
        for (int i = 0; i < texts.size(); i++) {
            JsonNode node = JSON.readTree(texts.get(i));
            rows.add(new Row(after + 1 + i, Instant.parse(node.path("time").asString()),
                    node.path("kind").asString(), node.path("payload")));
        }
        return rows;
    }

    private RedisCommands<String, String> commands() {
        String tenantId = TenantContext.getTenantId();
        if (tenantId == null) {
            throw new IllegalStateException("No tenant on this thread; a transcript belongs to one.");
        }
        return kvrocks.connection(tenantId).sync();
    }

    static String key(UUID runId, int attempt) {
        return "flow:transcript:" + runId + ":" + attempt;
    }
}
