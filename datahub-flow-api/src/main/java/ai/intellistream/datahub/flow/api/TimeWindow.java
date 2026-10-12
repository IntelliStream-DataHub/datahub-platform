// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.flow.api;

import java.time.Instant;
import java.util.Objects;

/**
 * A range of data time, {@code [start, end)}: the range a run's sinks write, as its sources saw it
 * when the run executed.
 */
public record TimeWindow(Instant start, Instant end) {

    public TimeWindow {
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(end, "end");
        if (!start.isBefore(end)) {
            throw new IllegalArgumentException("A window's start must be before its end: " + start
                    + " .. " + end);
        }
    }
}
