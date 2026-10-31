// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.flow.records;

import ai.intellistream.datahub.flow.records.RecordsTable.Position;
import ai.intellistream.datahub.flow.records.RecordsTable.StoredRecord;
import ai.intellistream.datahub.flow.store.RunStore.StoredRun;
import ai.intellistream.datahub.flow.runs.RunView;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.OutputStream;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * A run's live view: its state and its records as they land, written as newline-delimited JSON
 * ({@code application/x-ndjson}) until the run has ended.
 *
 * <p>NDJSON rather than server-sent events because the console reads it with {@code fetch} — the
 * browser's {@code EventSource} cannot send a bearer token — and a line is the simplest frame to
 * split a {@code fetch} body on. Each line is one object with a {@code type}:
 * <ul>
 *   <li>{@code run} — the run as {@code GET /runs/{id}} shows it, written first and again whenever
 *       its status or attempt changes;</li>
 *   <li>{@code records} — records that have landed since the last line, in order;</li>
 *   <li>{@code end} — the run has ended and its records have landed (or the view gave up waiting);
 *       nothing follows.</li>
 * </ul>
 *
 * <p>Polled rather than pushed: the run may execute on any instance, and its records reach
 * ClickHouse through Pulsar on whichever instance consumes them, so reading the run row and the
 * records table is the one view that is right wherever the run is. A poll is two indexed reads.
 * After the run ends the view keeps reading records until two polls in a row bring nothing new, as
 * the last batch lands a moment after the run finishes.
 */
final class RunEventStream {

    private static final Set<String> ENDED = Set.of("SUCCEEDED", "FAILED", "CANCELLED");
    static final int PAGE = 1000;

    private final UUID runId;
    private final Supplier<Optional<StoredRun>> run;
    private final Function<Position, List<StoredRecord>> records;
    private final JsonMapper json;
    private final Duration interval;
    private final Duration maxDuration;

    RunEventStream(UUID runId, Supplier<Optional<StoredRun>> run,
                   Function<Position, List<StoredRecord>> recordsAfter, JsonMapper json,
                   Duration interval, Duration maxDuration) {
        this.runId = runId;
        this.run = run;
        this.records = recordsAfter;
        this.json = json;
        this.interval = interval;
        this.maxDuration = maxDuration;
    }

    void write(OutputStream out) throws IOException {
        long deadline = System.nanoTime() + maxDuration.toNanos();
        String lastState = null;
        Position after = null;
        int quietPolls = 0;
        while (System.nanoTime() - deadline < 0) {
            Optional<StoredRun> current = run.get();
            if (current.isEmpty()) {
                break;   // deleted meanwhile
            }
            StoredRun r = current.get();
            String state = r.status() + "/" + r.attempt();
            if (!state.equals(lastState)) {
                line(out, "run", "run", json.valueToTree(RunView.of(r, json)));
                lastState = state;
            }
            boolean any = false;
            List<StoredRecord> page;
            do {
                page = records.apply(after);
                if (!page.isEmpty()) {
                    any = true;
                    StoredRecord last = page.getLast();
                    after = new Position(last.attempt(), last.sequence(), last.ordinal());
                    line(out, "records", "records", json.valueToTree(page));
                }
            } while (page.size() == PAGE);
            if (ENDED.contains(r.status())) {
                quietPolls = any ? 0 : quietPolls + 1;
                if (quietPolls >= 2) {
                    break;
                }
            }
            if (!sleep()) {
                break;
            }
        }
        ObjectNode end = json.createObjectNode();
        end.put("type", "end");
        end.put("run", runId.toString());
        out.write(json.writeValueAsBytes(end));
        out.write('\n');
        out.flush();
    }

    private void line(OutputStream out, String type, String field, JsonNode value) throws IOException {
        ObjectNode node = json.createObjectNode();
        node.put("type", type);
        node.set(field, value);
        out.write(json.writeValueAsBytes(node));
        out.write('\n');
        out.flush();
    }

    private boolean sleep() {
        try {
            Thread.sleep(interval);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
