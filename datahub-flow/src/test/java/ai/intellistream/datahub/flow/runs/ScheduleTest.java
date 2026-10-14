// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.flow.runs;

import ai.intellistream.datahub.flow.api.TimeWindow;
import ai.intellistream.datahub.flow.definition.FlowDefinitions;
import ai.intellistream.datahub.flow.fake.ExampleFlow;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Ticks, windows and catch-up: the arithmetic every sweep and every manual run leans on. */
class ScheduleTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final ZoneId OSLO = ZoneId.of("Europe/Oslo");

    private static Schedule hourly(Duration delay) {
        return new Schedule(Schedule.SCHEDULE, "0 0 * * * *", OSLO, Duration.ofHours(1), delay);
    }

    @Test
    void theTickAtTCoversTheWindowBeforeItAndIsDueAfterTheDelay() {
        Schedule s = hourly(Duration.ofMinutes(5));
        Instant tick = Instant.parse("2026-10-12T11:00:00Z");

        assertThat(s.windowOf(tick)).isEqualTo(new TimeWindow(Instant.parse("2026-10-12T10:00:00Z"), tick));
        assertThat(s.dueAt(tick)).isEqualTo(Instant.parse("2026-10-12T11:05:00Z"));
        assertThat(s.nextTick(Instant.parse("2026-10-12T10:59:59Z"))).isEqualTo(tick);
        assertThat(s.nextTick(tick)).isEqualTo(Instant.parse("2026-10-12T12:00:00Z"));
    }

    @Test
    void withoutAWindowATickCoversTheTimeSinceTheTickBeforeIt() {
        Schedule hourly = new Schedule(Schedule.SCHEDULE, "0 0 * * * *", OSLO, null, null);
        Instant tick = Instant.parse("2026-10-12T11:00:00Z");
        assertThat(hourly.windowOf(tick)).isEqualTo(new TimeWindow(Instant.parse("2026-10-12T10:00:00Z"), tick));

        // Weekday mornings: Monday's run covers the weekend too.
        Schedule weekdays = new Schedule(Schedule.SCHEDULE, "0 0 9 * * MON-FRI", ZoneId.of("UTC"), null, null);
        Instant monday = Instant.parse("2026-10-12T09:00:00Z");
        assertThat(weekdays.windowOf(monday)).isEqualTo(new TimeWindow(Instant.parse("2026-10-09T09:00:00Z"), monday));

        // A daylight-saving change makes that day's window 25 hours.
        Schedule daily = new Schedule(Schedule.SCHEDULE, "0 0 6 * * *", OSLO, null, null);
        Instant after = Instant.parse("2026-10-25T05:00:00Z");
        assertThat(daily.windowOf(after)).isEqualTo(new TimeWindow(Instant.parse("2026-10-24T04:00:00Z"), after));
    }

    @Test
    void thePreviousTickIsStrictlyBefore() {
        Schedule s = new Schedule(Schedule.SCHEDULE, "*/10 * * * * *", OSLO, null, null);

        assertThat(s.previousTick(Instant.parse("2026-10-12T11:00:00Z"))).isEqualTo(Instant.parse("2026-10-12T10:59:50Z"));
        assertThat(s.previousTick(Instant.parse("2026-10-12T11:00:00.5Z"))).isEqualTo(Instant.parse("2026-10-12T11:00:00Z"));
        // Every 29 February: four years back.
        Schedule leap = new Schedule(Schedule.SCHEDULE, "0 0 0 29 2 *", ZoneId.of("UTC"), null, null);
        assertThat(leap.previousTick(Instant.parse("2026-10-12T00:00:00Z"))).isEqualTo(Instant.parse("2024-02-29T00:00:00Z"));
    }

    @Test
    void aDailyTickFollowsTheWallClockAcrossADaylightSavingChange() {
        // 06:00 in Oslo: 04:00 UTC in summer, 05:00 UTC once the clocks go back at 03:00 on
        // 25 October.
        Schedule daily = new Schedule(Schedule.SCHEDULE, "0 0 6 * * *", OSLO, Duration.ofDays(1), null);

        Instant before = daily.nextTick(Instant.parse("2026-10-23T12:00:00Z"));
        Instant after = daily.nextTick(before);

        assertThat(before).isEqualTo(Instant.parse("2026-10-24T04:00:00Z"));
        assertThat(after).isEqualTo(Instant.parse("2026-10-25T05:00:00Z"));
        assertThat(Duration.between(before, after)).isEqualTo(Duration.ofHours(25));
    }

    @Test
    void catchUpCreatesTheMissedTicksOldestFirstUpToTheBound() {
        Schedule s = hourly(Duration.ofMinutes(5));
        Instant first = Instant.parse("2026-10-12T00:00:00Z");

        Schedule.Due due = s.due(first, Instant.parse("2026-10-12T10:30:00Z"), 24);
        assertThat(due.ticks()).hasSize(11).first().isEqualTo(first);
        assertThat(due.ticks().getLast()).isEqualTo(Instant.parse("2026-10-12T10:00:00Z"));
        assertThat(due.next()).isEqualTo(Instant.parse("2026-10-12T11:00:00Z"));

        Schedule.Due bounded = s.due(first, Instant.parse("2026-10-14T00:00:00Z"), 24);
        assertThat(bounded.ticks()).hasSize(24);
        assertThat(bounded.next()).isEqualTo(Instant.parse("2026-10-13T00:00:00Z"));
    }

    @Test
    void aTickIsNotDueBeforeItsDelayHasPassed() {
        Schedule s = hourly(Duration.ofMinutes(5));
        Instant tick = Instant.parse("2026-10-12T11:00:00Z");

        assertThat(s.due(tick, Instant.parse("2026-10-12T11:04:59Z"), 24).ticks()).isEmpty();
        assertThat(s.due(tick, Instant.parse("2026-10-12T11:05:00Z"), 24).ticks()).containsExactly(tick);
    }

    @Test
    void aManualRunDefaultsToTheLatestDueWindow() {
        Schedule s = hourly(Duration.ofMinutes(5));

        assertThat(s.defaultWindow(Instant.parse("2026-10-12T11:03:00Z"))).isEqualTo(new TimeWindow(
                Instant.parse("2026-10-12T09:00:00Z"), Instant.parse("2026-10-12T10:00:00Z")));
        assertThat(s.defaultWindow(Instant.parse("2026-10-12T11:06:00Z"))).isEqualTo(new TimeWindow(
                Instant.parse("2026-10-12T10:00:00Z"), Instant.parse("2026-10-12T11:00:00Z")));

        Schedule derived = new Schedule(Schedule.SCHEDULE, "0 0 * * * *", OSLO, null, Duration.ofMinutes(5));
        assertThat(derived.defaultWindow(Instant.parse("2026-10-12T11:05:00Z"))).isEqualTo(new TimeWindow(
                Instant.parse("2026-10-12T10:00:00Z"), Instant.parse("2026-10-12T11:00:00Z")));
        assertThat(Schedule.fromJson(JSON, derived.toJson(JSON))).isEqualTo(derived);

        Schedule manual = new Schedule(Schedule.MANUAL, null, null, Duration.ofMinutes(30), null);
        assertThat(manual.defaultWindow(Instant.parse("2026-10-12T11:06:07.5Z"))).isEqualTo(new TimeWindow(
                Instant.parse("2026-10-12T10:36:07Z"), Instant.parse("2026-10-12T11:06:07Z")));
        assertThat(new Schedule(Schedule.MANUAL, null, null, null, null)
                .defaultWindow(Instant.now())).isNull();
    }

    @Test
    void theDeployedTriggerResolvesAWindowParameterToItsDefaultAndSurvivesStorage() {
        Schedule s = Schedule.of(FlowDefinitions.parse(ExampleFlow.DEFINITION));

        assertThat(s).isEqualTo(new Schedule(Schedule.SCHEDULE, "0 0 * * * *", OSLO,
                Duration.ofHours(1), Duration.ofMinutes(5)));
        assertThat(Schedule.fromJson(JSON, s.toJson(JSON))).isEqualTo(s);

        var noDefault = ExampleFlow.tree();
        ((tools.jackson.databind.node.ObjectNode) noDefault.get("parameters").get("window")).remove("default");
        assertThatThrownBy(() -> Schedule.of(FlowDefinitions.parse(noDefault.toString())))
                .hasMessageContaining("no default");
    }
}
