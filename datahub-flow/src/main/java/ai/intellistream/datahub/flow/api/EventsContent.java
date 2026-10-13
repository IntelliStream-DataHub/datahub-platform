// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.flow.api;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A batch of event transitions in the shape of DataHub's event model: one row per transition. A
 * period — an alarm that starts and later ends — is two rows sharing an {@code externalId}, one
 * {@code active} and one {@code cleared}, which is how DataHub records a lifecycle.
 *
 * <p>Held on the heap; the engine counts {@link #size()} against the run's memory cap.
 */
public final class EventsContent implements Content {

    private final List<EventRow> rows;
    private final long size;

    private EventsContent(List<EventRow> rows) {
        this.rows = List.copyOf(rows);
        long bytes = 0;
        for (EventRow row : this.rows) {
            bytes += row.estimatedSize();
        }
        this.size = bytes;
    }

    public static EventsContent of(List<EventRow> rows) {
        return new EventsContent(rows);
    }

    @Override
    public ContentKind kind() {
        return ContentKind.EVENTS;
    }

    public List<EventRow> rows() {
        return rows;
    }

    @Override
    public long size() {
        return size;
    }

    /** One event transition. {@code status} and {@code metadata} may be empty, never null. */
    public record EventRow(String externalId, Instant eventTime, String status,
                           Map<String, String> metadata) {

        public EventRow {
            Objects.requireNonNull(externalId, "externalId");
            Objects.requireNonNull(eventTime, "eventTime");
            status = status == null ? "" : status;
            metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        }

        long estimatedSize() {
            long bytes = 64 + 2L * externalId.length() + 2L * status.length();
            for (Map.Entry<String, String> e : metadata.entrySet()) {
                bytes += 48 + 2L * e.getKey().length() + 2L * e.getValue().length();
            }
            return bytes;
        }
    }
}
