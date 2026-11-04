// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.flow.runs;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the Runs overview calls a problem, a warning or fine: each rule alone, and that a problem
 * outranks a warning.
 */
class RunBoardHealthTest {

    private static final Instant NOW = Instant.parse("2026-10-16T12:00:00Z");
    private static final Duration LONG = RunService.BEHIND.plusMinutes(1);

    /** A deployed, switched-on flow with nothing going on and nothing wrong. */
    private static Row fine() {
        return new Row();
    }

    @Test
    void aFlowWithNothingWrongIsOk() {
        assertThat(entry(fine())).satisfies(e -> {
            assertThat(e.health()).isEqualTo("OK");
            assertThat(e.reasons()).isEmpty();
        });
    }

    @Test
    void aFlowSwitchedOffByAPersonIsOffAndNotAProblem() {
        assertThat(entry(fine().enabled(false)).health()).isEqualTo("OFF");
    }

    @Test
    void aFlowSwitchedOffByTheServiceIsAProblem() {
        assertThat(entry(fine().enabled(false).disabledReason("DATASET_DELETED"))).satisfies(e -> {
            assertThat(e.health()).isEqualTo("PROBLEM");
            assertThat(e.reasons()).containsExactly("SWITCHED_OFF");
        });
    }

    @Test
    void aFailedLatestRunIsAProblemAndASuccessIsNot() {
        assertThat(entry(fine().last("FAILED"))).satisfies(e -> {
            assertThat(e.health()).isEqualTo("PROBLEM");
            assertThat(e.reasons()).containsExactly("LAST_RUN_FAILED");
            assertThat(e.lastRun().status()).isEqualTo("FAILED");
        });
        assertThat(entry(fine().last("SUCCEEDED")).health()).isEqualTo("OK");
    }

    @Test
    void aDeploymentThatNeedsReauthorisationIsAProblem() {
        assertThat(entry(fine().identity("needs-reauthorisation")).reasons())
                .containsExactly("NEEDS_REAUTHORISATION");
    }

    @Test
    void aRunBeingRetriedIsOnlyAWarning() {
        assertThat(entry(fine().retrying(1))).satisfies(e -> {
            assertThat(e.health()).isEqualTo("WARNING");
            assertThat(e.reasons()).containsExactly("RETRYING");
        });
    }

    @Test
    void aRunWaitingLongerThanTheLimitIsABacklogAndOneWaitingLessIsNot() {
        assertThat(entry(fine().queued(3, NOW.minus(LONG)))).satisfies(e -> {
            assertThat(e.health()).isEqualTo("WARNING");
            assertThat(e.reasons()).containsExactly("BACKLOG");
        });
        assertThat(entry(fine().queued(3, NOW.minus(Duration.ofMinutes(1)))).health()).isEqualTo("OK");
    }

    @Test
    void aTickDueLongAgoThatNoRunWasMadeForIsOverdue() {
        assertThat(entry(fine().nextDue(NOW.minus(LONG))).reasons()).containsExactly("OVERDUE");
        assertThat(entry(fine().nextDue(NOW.plus(Duration.ofMinutes(10)))).health()).isEqualTo("OK");
    }

    @Test
    void aProblemOutranksAWarning() {
        assertThat(entry(fine().last("FAILED").retrying(1))).satisfies(e -> {
            assertThat(e.health()).isEqualTo("PROBLEM");
            assertThat(e.reasons()).containsExactly("LAST_RUN_FAILED", "RETRYING");
        });
    }

    private static ai.intellistream.datahub.flow.runs.RunView.BoardEntry entry(Row row) {
        return RunService.entry(row.build(), NOW);
    }

    /** A board row built up from fine, one thing at a time. */
    private static final class Row {
        private boolean enabled = true;
        private String disabledReason;
        private String identity = "ok";
        private Instant nextDue;
        private int queued;
        private int retrying;
        private Instant waiting;
        private String lastStatus;

        Row enabled(boolean v) { enabled = v; return this; }
        Row disabledReason(String v) { disabledReason = v; return this; }
        Row identity(String v) { identity = v; return this; }
        Row nextDue(Instant v) { nextDue = v; return this; }
        Row retrying(int v) { retrying = v; queued = Math.max(queued, v); return this; }
        Row queued(int n, Instant since) { queued = n; waiting = since; return this; }
        Row last(String v) { lastStatus = v; return this; }

        RunService.BoardRow build() {
            return new RunService.BoardRow(1, "flow", "Flow", 1, enabled, disabledReason, identity, nextDue, 0, null, null,
                    queued, retrying, waiting, lastStatus == null ? null : UUID.randomUUID(), lastStatus,
                    null, lastStatus == null ? null : NOW.minusSeconds(60));
        }
    }
}
