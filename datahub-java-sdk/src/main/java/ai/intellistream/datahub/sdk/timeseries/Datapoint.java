// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.sdk.timeseries;

import ai.intellistream.datahub.api.responses.DatapointString;

import java.time.Instant;

/**
 * A single datapoint to ingest — a timestamp and a value. The {@code of(...)} factories build the
 * string value the wire expects; {@link #toDatapointString()} converts to the wire type. Used by
 * {@link ai.intellistream.datahub.sdk.services.TimeseriesService#ingest(java.util.Map)}.
 */
public record Datapoint(Instant timestamp, String value) {

    public static Datapoint of(Instant timestamp, double value) {
        return new Datapoint(timestamp, Double.toString(value));
    }

    public static Datapoint of(Instant timestamp, long value) {
        return new Datapoint(timestamp, Long.toString(value));
    }

    /** Text or already-formatted value (e.g. for {@code TEXT}/{@code NUMERIC} series). */
    public static Datapoint of(Instant timestamp, String value) {
        return new Datapoint(timestamp, value);
    }

    public static Datapoint of(long epochMillis, double value) {
        return new Datapoint(Instant.ofEpochMilli(epochMillis), Double.toString(value));
    }

    /** The wire form: timestamp as epoch milliseconds, value as a string. */
    public DatapointString toDatapointString() {
        return new DatapointString(Long.toString(timestamp.toEpochMilli()), value);
    }
}
