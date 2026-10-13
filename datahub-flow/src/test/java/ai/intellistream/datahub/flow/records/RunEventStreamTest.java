// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.flow.records;

import ai.intellistream.datahub.flow.records.RecordsTable.Position;
import ai.intellistream.datahub.flow.records.RecordsTable.StoredRecord;
import ai.intellistream.datahub.flow.runs.RunRepository.StoredRun;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The live view's framing: a {@code run} line when the run's state changes, {@code records} lines
 * as records land, read on from where the last ones ended, and an {@code end} line once the run has
 * ended and its records have stopped arriving.
 */
class RunEventStreamTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final UUID RUN = UUID.fromString("0192a6b0-0000-7000-8000-000000000001");

    @Test
    void aRunIsFollowedUntilItHasEndedAndItsRecordsHaveLanded() throws Exception {
        Deque<String> statuses = new ArrayDeque<>(List.of("PENDING", "RUNNING", "RUNNING", "SUCCEEDED"));
        List<Position> asked = new ArrayList<>();
        Deque<List<StoredRecord>> landing = new ArrayDeque<>(List.of(
                List.of(), List.of(record(0, 0), record(0, 1)), List.of(), List.of(record(1, 0))));

        RunEventStream stream = new RunEventStream(RUN,
                () -> Optional.of(run(statuses.size() > 1 ? statuses.pop() : statuses.peek())),
                after -> {
                    asked.add(after);
                    return landing.isEmpty() ? List.of() : landing.pop();
                },
                JSON, Duration.ofMillis(1), Duration.ofSeconds(10));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        stream.write(out);

        List<JsonNode> lines = out.toString(StandardCharsets.UTF_8).lines().map(JSON::readTree).toList();
        assertThat(lines).extracting(l -> l.get("type").asString())
                .containsExactly("run", "run", "records", "run", "records", "end");
        assertThat(lines.get(0).get("run").get("status").asString()).isEqualTo("PENDING");
        assertThat(lines.get(1).get("run").get("status").asString()).isEqualTo("RUNNING");
        assertThat(lines.get(2).get("records")).hasSize(2);
        assertThat(lines.get(3).get("run").get("status").asString()).isEqualTo("SUCCEEDED");
        assertThat(lines.get(4).get("records")).hasSize(1);
        // Each read starts where the previous records ended.
        assertThat(asked).contains(new Position(1, 0, 1));
        assertThat(asked.getFirst()).isNull();
    }

    @Test
    void aRunDeletedMeanwhileEndsTheView() throws Exception {
        RunEventStream stream = new RunEventStream(RUN, Optional::empty, after -> List.of(), JSON,
                Duration.ofMillis(1), Duration.ofSeconds(10));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        stream.write(out);

        assertThat(out.toString(StandardCharsets.UTF_8).strip()).contains("\"type\":\"end\"");
    }

    private static StoredRecord record(int sequence, int ordinal) {
        return new StoredRecord(Instant.parse("2026-10-12T10:00:00Z"), 1, sequence, ordinal, "out",
                "timeseries.write", "send", UUID.randomUUID(), List.of(), "success", "timeseries",
                100, "42", Map.of(), "datahub://timeseries/ts_out", 3, false);
    }

    private static StoredRun run(String status) {
        return new StoredRun(RUN, 1L, "temp_hourly", 7L, 1, status, 1, 2, Instant.EPOCH, false, false,
                false, 1 << 20, Duration.ofMinutes(15), null, null, null, null, "manual", "user-1",
                "user-1", Instant.EPOCH, null, null, null, null, null);
    }
}
