// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.sdk.ingest;

import ai.intellistream.datahub.api.responses.DatapointString;
import ai.intellistream.datahub.api.responses.DatapointsCollection;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Durable spool for datapoint ingestion: a {@link DurableSpool} of individual datapoints (so the
 * time/size retention bounds apply per datapoint), with the flattening to and grouping from
 * {@link DatapointsCollection} that the timeseries ingest path needs.
 */
public final class DatapointSpool {

    /** One spooled datapoint: its series external id and the wire timestamp/value (both strings). */
    public record SpoolLine(String externalId, String timestamp, String value) {
    }

    private final DurableSpool<SpoolLine> spool;

    public DatapointSpool(Path dir, Duration retention, Long maxBytes, JsonMapper mapper) {
        this.spool = new DurableSpool<>(dir, retention, maxBytes, mapper, SpoolLine.class,
                line -> {
                    try {
                        return Long.parseLong(line.timestamp().trim());
                    } catch (NumberFormatException | NullPointerException e) {
                        return Long.MIN_VALUE;
                    }
                });
    }

    /** Records currently spooled. */
    public long size() {
        return spool.size();
    }

    /** Append failed datapoints to the spool (after enforcing the retention bounds). */
    public void append(List<DatapointsCollection> data, long nowMillis) {
        spool.append(flatten(data), nowMillis);
    }

    /** Stream the spool to {@code sender} in datapoint-collection chunks; see {@link DurableSpool#drain}. */
    public boolean drain(DurableSpool.ChunkSender<DatapointsCollection> sender, long nowMillis) {
        return spool.drain(lines -> sender.send(group(lines)), nowMillis);
    }

    public void clear() {
        spool.clear();
    }

    private static List<SpoolLine> flatten(List<DatapointsCollection> data) {
        List<SpoolLine> lines = new ArrayList<>();
        for (DatapointsCollection c : data) {
            if (c.getDatapoints() == null) {
                continue;
            }
            for (DatapointString d : c.getDatapoints()) {
                lines.add(new SpoolLine(c.getExternalId(), d.getTimestamp(), d.getValue()));
            }
        }
        return lines;
    }

    private static List<DatapointsCollection> group(List<SpoolLine> lines) {
        Map<String, DatapointsCollection> byExternalId = new LinkedHashMap<>();
        for (SpoolLine line : lines) {
            DatapointsCollection c = byExternalId.computeIfAbsent(line.externalId(), k -> {
                DatapointsCollection dc = new DatapointsCollection();
                dc.setExternalId(k);
                dc.setDatapoints(new ArrayList<>());
                return dc;
            });
            c.getDatapoints().add(new DatapointString(line.timestamp(), line.value()));
        }
        return new ArrayList<>(byExternalId.values());
    }
}
