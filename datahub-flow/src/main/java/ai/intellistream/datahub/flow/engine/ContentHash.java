// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.flow.engine;

import ai.intellistream.datahub.flow.api.Content;
import ai.intellistream.datahub.flow.api.EventsContent;
import ai.intellistream.datahub.flow.api.EventsContent.EventRow;
import ai.intellistream.datahub.flow.api.TimeseriesContent;
import net.openhft.hashing.LongHashFunction;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The hash recorded for an item's content: what the data was when a run touched it, so that a
 * re-read can be compared with it later ("has the input changed since?", "did two runs see the same
 * data?", "did this step change it?").
 *
 * <p>It is taken over a canonical form, not over the content's in-memory layout, so that the same
 * data read again hashes the same however it was laid out:
 * <ul>
 *   <li><b>timeseries</b> — rows sorted by timestamp (then by value), each the timestamp in
 *       milliseconds and the value's IEEE-754 bits ({@link Double#doubleToLongBits}, so every NaN
 *       is the same NaN), both 8 bytes little-endian;</li>
 *   <li><b>events</b> — rows sorted by event time, external id, status, then metadata; each the
 *       event time in milliseconds (8 bytes LE), then external id and status, then the metadata's
 *       entry count and its entries sorted by key, every string as its UTF-8 length (4 bytes LE)
 *       followed by its bytes.</li>
 * </ul>
 * The form starts with the content kind's name and a zero byte, so empty content of two kinds does
 * not collide.
 *
 * <p>XXH3, the function {@code IdGenerator.xxHash} uses: it detects change, it is not a seal
 * against deliberate tampering. The form above is a contract — changing it makes every recorded
 * hash incomparable with new ones — and {@code ContentHashTest} pins it.
 */
public final class ContentHash {

    private static final LongHashFunction XX3 = LongHashFunction.xx3();

    private ContentHash() {
    }

    public static long of(Content content) {
        return switch (content) {
            case TimeseriesContent ts -> timeseries(ts);
            case EventsContent events -> events(events);
        };
    }

    private static long timeseries(TimeseriesContent ts) {
        int n = ts.rowCount();
        long[][] rows = new long[n][2];
        for (int i = 0; i < n; i++) {
            rows[i][0] = ts.timestamp(i);
            rows[i][1] = Double.doubleToLongBits(ts.value(i));
        }
        Arrays.sort(rows, Comparator.<long[]>comparingLong(r -> r[0])
                .thenComparingLong(r -> r[1]));
        byte[] kind = header(ts);
        ByteBuffer buf = ByteBuffer.allocate(kind.length + n * 16).order(ByteOrder.LITTLE_ENDIAN);
        buf.put(kind);
        for (long[] row : rows) {
            buf.putLong(row[0]).putLong(row[1]);
        }
        return XX3.hashBytes(buf.array());
    }

    private static long events(EventsContent events) {
        List<EventRow> rows = new ArrayList<>(events.rows());
        rows.sort(Comparator.comparing(EventRow::eventTime)
                .thenComparing(EventRow::externalId)
                .thenComparing(EventRow::status)
                .thenComparing(r -> new TreeMap<>(r.metadata()).toString()));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(header(events));
        ByteBuffer eight = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
        for (EventRow row : rows) {
            out.writeBytes(eight.clear().putLong(row.eventTime().toEpochMilli()).array());
            string(out, row.externalId());
            string(out, row.status());
            out.writeBytes(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
                    .putInt(row.metadata().size()).array());
            for (Map.Entry<String, String> e : new TreeMap<>(row.metadata()).entrySet()) {
                string(out, e.getKey());
                string(out, e.getValue());
            }
        }
        return XX3.hashBytes(out.toByteArray());
    }

    private static byte[] header(Content content) {
        byte[] name = content.kind().wireName().getBytes(StandardCharsets.UTF_8);
        byte[] header = new byte[name.length + 1];
        System.arraycopy(name, 0, header, 0, name.length);
        return header;
    }

    private static void string(ByteArrayOutputStream out, String s) {
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        out.writeBytes(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(bytes.length).array());
        out.writeBytes(bytes);
    }
}
