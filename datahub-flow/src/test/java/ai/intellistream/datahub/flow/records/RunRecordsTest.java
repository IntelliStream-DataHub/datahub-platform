// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.flow.records;

import ai.intellistream.datahub.flow.api.ContentKind;
import ai.intellistream.datahub.flow.engine.FlowRecord;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A run attempt's records go out in batches — every thousand, or every five seconds, and the rest
 * at the end — numbered from 0, capped with a marker, and the attempt is finished only once every
 * batch is acknowledged.
 */
class RunRecordsTest {

    private static final UUID RUN = UUID.fromString("0192a6b0-0000-7000-8000-000000000001");

    private final List<FlowRecordBatch> sent = new ArrayList<>();
    private final MutableClock clock = new MutableClock();

    @Test
    void recordsGoOutEveryThousandAndTheRestAtTheEnd() throws Exception {
        RunRecords records = records(100_000, batch -> ack(batch));
        for (int i = 0; i < 2_500; i++) {
            records.accept(record());
        }
        assertThat(sent).extracting(b -> b.getRecords().size()).containsExactly(1_000, 1_000);

        records.finish(Duration.ofSeconds(1));

        assertThat(sent).extracting(FlowRecordBatch::getSequence).containsExactly(0, 1, 2);
        assertThat(sent).extracting(b -> b.getRecords().size()).containsExactly(1_000, 1_000, 500);
        assertThat(sent).allSatisfy(b -> {
            assertThat(b.getRunId()).isEqualTo(RUN.toString());
            assertThat(b.getAttempt()).isEqualTo(2);
            assertThat(b.getFlowExternalId()).isEqualTo("temp_hourly");
            assertThat(b.getFlowVersion()).isEqualTo(7);
        });
    }

    @Test
    void recordsGoOutAfterFiveSecondsEvenWhenFew() {
        RunRecords records = records(100_000, batch -> ack(batch));
        records.accept(record());
        clock.advance(Duration.ofSeconds(4));
        records.accept(record());
        assertThat(sent).isEmpty();

        clock.advance(Duration.ofSeconds(1));
        records.accept(record());

        assertThat(sent).singleElement().satisfies(b -> assertThat(b.getRecords()).hasSize(3));
    }

    @Test
    void anAttemptOverTheCapKeepsTheFirstRecordsAndEndsWithAMarker() throws Exception {
        RunRecords records = records(3, batch -> ack(batch));
        for (int i = 0; i < 5; i++) {
            records.accept(record());
        }
        records.finish(Duration.ofSeconds(1));

        assertThat(records.truncated()).isTrue();
        List<FlowRecordBatch.Row> rows = sent.getFirst().getRecords();
        assertThat(rows).extracting(FlowRecordBatch.Row::getAction)
                .containsExactly("send", "send", "send", FlowRecordBatch.Row.TRUNCATED);
        assertThat(rows.getLast().isTruncated()).isTrue();
        assertThat(rows.subList(0, 3)).noneMatch(FlowRecordBatch.Row::isTruncated);
    }

    @Test
    void anAttemptWithNoRecordsPublishesNothing() throws Exception {
        records(100, batch -> ack(batch)).finish(Duration.ofSeconds(1));

        assertThat(sent).isEmpty();
    }

    @Test
    void anAttemptIsNotFinishedWhileABatchIsUnacknowledged() {
        CompletableFuture<Void> never = new CompletableFuture<>();
        RunRecords records = records(100, batch -> never);
        records.accept(record());

        assertThatThrownBy(() -> records.finish(Duration.ofMillis(50)))
                .isInstanceOf(RunRecords.UnpublishedException.class)
                .hasMessageContaining("did not acknowledge");
    }

    @Test
    void aRefusedBatchFailsTheFinish() {
        RunRecords records = records(100, batch -> {
            throw new IllegalStateException("backlog quota exceeded");
        });
        records.accept(record());

        assertThatThrownBy(() -> records.finish(Duration.ofSeconds(1)))
                .isInstanceOf(RunRecords.UnpublishedException.class)
                .hasMessageContaining("backlog quota exceeded");
    }

    @Test
    void aRowCarriesWhatTheEngineRecorded() {
        UUID item = UUID.randomUUID();
        UUID parent = UUID.randomUUID();
        FlowRecord record = new FlowRecord(Instant.parse("2026-10-12T10:00:00.123Z"), RUN, 2, "out",
                "write-timeseries", FlowRecord.Action.ATTRIBUTES_MODIFIED, item, List.of(parent),
                "success", ContentKind.TIMESERIES, 640, -2, Map.of("k", "v"),
                "datahub://timeseries/ts_out?from=a&to=b", 12);

        FlowRecordBatch.Row row = FlowRecordBatch.Row.of(record);

        assertThat(row.getTimeMillis()).isEqualTo(Instant.parse("2026-10-12T10:00:00.123Z").toEpochMilli());
        assertThat(row.getAction()).isEqualTo("attributes_modified");
        assertThat(row.getContentKind()).isEqualTo("timeseries");
        assertThat(row.getItemId()).isEqualTo(item.toString());
        assertThat(row.getParentItemIds()).containsExactly(parent.toString());
        assertThat(row.getHash()).isEqualTo(-2);
        assertThat(row.getChangedAttributes()).containsEntry("k", "v");
        assertThat(row.getDurationMillis()).isEqualTo(12);
    }

    private RunRecords records(long cap, java.util.function.Function<FlowRecordBatch, CompletableFuture<?>> send) {
        return new RunRecords(RUN, 2, "temp_hourly", 7, cap, clock, send);
    }

    private CompletableFuture<?> ack(FlowRecordBatch batch) {
        sent.add(batch);
        return CompletableFuture.completedFuture(null);
    }

    private FlowRecord record() {
        return new FlowRecord(clock.instant(), RUN, 2, "out", "write-timeseries",
                FlowRecord.Action.SEND, UUID.randomUUID(), List.of(), null, ContentKind.TIMESERIES,
                100, 42, Map.of(), "datahub://timeseries/ts_out", 3);
    }

    /** A clock the test moves by hand. */
    static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-10-12T10:00:00Z");

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
