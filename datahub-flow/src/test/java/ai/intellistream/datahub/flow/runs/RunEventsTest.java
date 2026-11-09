// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.flow.runs;

import ai.intellistream.datahub.flow.api.TimeWindow;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** The lifecycle key a flow and window's failures share, and the ids of the events in it. */
class RunEventsTest {

    private static final TimeWindow WINDOW = new TimeWindow(Instant.parse("2026-10-12T10:00:00Z"),
            Instant.parse("2026-10-12T11:00:00Z"));

    @Test
    void aLifecycleIsTheFlowAndItsWindowStart() {
        assertThat(RunEvents.externalId("temp_hourly", WINDOW, UUID.randomUUID()))
                .isEqualTo("flow:temp_hourly:2026-10-12T10:00:00Z");
    }

    @Test
    void aRunWithoutAWindowIsItsOwnLifecycle() {
        UUID run = UUID.fromString("0190b4c2-1111-7000-8000-000000000001");
        assertThat(RunEvents.externalId("listener", null, run)).isEqualTo("flow:listener:" + run);
    }

    @Test
    void aVeryLongFlowIdIsShortenedToFitTheEventsApiAndStaysDistinct() {
        String a = "a".repeat(255);
        String b = "a".repeat(254) + "b";

        String idA = RunEvents.externalId(a, WINDOW, UUID.randomUUID());
        String idB = RunEvents.externalId(b, WINDOW, UUID.randomUUID());

        assertThat(idA).hasSizeLessThanOrEqualTo(256).endsWith(":2026-10-12T10:00:00Z");
        assertThat(idB).hasSizeLessThanOrEqualTo(256);
        assertThat(idA).isNotEqualTo(idB);
        assertThat(RunEvents.externalId(a, WINDOW, UUID.randomUUID())).isEqualTo(idA);
    }

    @Test
    void anEventsIdFollowsFromWhatItSaysSoARetryIsTheSameEvent() {
        UUID open = RunEvents.id("flow:x:2026-10-12T10:00:00Z", 1_760_263_200_000L, "open");

        assertThat(RunEvents.id("flow:x:2026-10-12T10:00:00Z", 1_760_263_200_000L, "open")).isEqualTo(open);
        assertThat(RunEvents.id("flow:x:2026-10-12T10:00:00Z", 1_760_263_200_000L, "resolved"))
                .isNotEqualTo(open);
        assertThat(open.version()).isEqualTo(7);
        assertThat(open.getMostSignificantBits() >>> 16).isEqualTo(1_760_263_200_000L);
    }
}
