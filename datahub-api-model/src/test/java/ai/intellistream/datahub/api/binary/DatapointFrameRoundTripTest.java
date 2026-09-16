// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.api.binary;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a frame written by {@link DatapointFrameWriter} looks like once {@link DatapointFrame} has
 * read it back: rows sorted, duplicates collapsed to the last value, the directory equal to the
 * series in the payload, every value type's string form intact, and the bytes forwarded verbatim.
 */
class DatapointFrameRoundTripTest {

    static final PayloadCodec ZSTD = new ZstdPayloadCodec(1);

    @ParameterizedTest
    @ValueSource(ints = {1, 3, 9})
    void everyZstdLevelRoundTrips(int level) {
        DatapointFrameWriter w = DatapointFrameWriter.forType(DatapointValueType.FLOAT32).series(7, "pump_a");
        for (int i = 0; i < 1000; i++) {
            w.addFloat32(7, 1_700_000_000_000L + i * 1000L, i / 10f);
        }
        List<DatapointFrame> frames = DatapointFrame.parseAll(w.build(new ZstdPayloadCodec(level)), new ZstdPayloadCodec(level));
        assertEquals(1, frames.size());
        assertEquals(1000, frames.get(0).rowCount());
        assertEquals("99.9", frames.get(0).valueAsString(999));
    }

    @Test
    void sortsAndKeepsTheLastValueOfARepeatedPair() {
        // Rows arrive out of order and one pair twice; the frame is sorted and the later value wins.
        DatapointFrameWriter w = DatapointFrameWriter.forType(DatapointValueType.BIGINT)
                .series(20, "second").series(10, "first");
        w.addBigint(20, 5000, 1);
        w.addBigint(10, 3000, 2);
        w.addBigint(10, 1000, 3);
        w.addBigint(20, 4000, 4);
        w.addBigint(10, 3000, 5);
        byte[] built = w.build(ZSTD);

        DatapointFrame f = DatapointFrame.parseAll(built, ZSTD).get(0);
        assertEquals(4, f.rowCount());
        assertEquals(2, f.seriesCount());
        assertArrayEquals(new long[]{10, 20}, f.seriesIds());
        assertArrayEquals(new String[]{"first", "second"}, f.externalIds());
        assertEquals(List.of(new DatapointFrame.Run(10, "first", 0, 2), new DatapointFrame.Run(20, "second", 2, 4)), f.runs());
        assertEquals(1000, f.timestamp(0));
        assertEquals("3", f.valueAsString(0));
        assertEquals(3000, f.timestamp(1));
        assertEquals("5", f.valueAsString(1));
        assertEquals("4", f.valueAsString(2));
        assertEquals("1", f.valueAsString(3));
        assertArrayEquals(built, f.frameBytes());
    }

    @Test
    void everyValueTypeKeepsItsStringForm() {
        assertEquals("-42", roundTrip(DatapointValueType.BIGINT, "-42"));
        assertEquals("22.4", roundTrip(DatapointValueType.FLOAT, "22.4"));
        assertEquals("1.5", roundTrip(DatapointValueType.FLOAT32, "1.5"));
        // NUMERIC is Decimal(18, 6): rounded half-up to six places, printed with all six.
        assertEquals("12.345679", roundTrip(DatapointValueType.NUMERIC, "12.3456789"));
        assertEquals("-0.000001", roundTrip(DatapointValueType.NUMERIC, "-0.0000005"));
        // DECIMAL32 is Decimal(9, 4): rounded half-up to four places and clamped to +/-99999.9999.
        assertEquals("3.1416", roundTrip(DatapointValueType.DECIMAL32, "3.14159"));
        assertEquals("99999.9999", roundTrip(DatapointValueType.DECIMAL32, "123456"));
        assertEquals("høyde", roundTrip(DatapointValueType.TEXT, "høyde"));
        assertEquals("7.25", roundTrip(DatapointValueType.MIXED, "7.25"));
        assertEquals("open", roundTrip(DatapointValueType.MIXED, "open"));
    }

    @Test
    void mixedFramesCarryNumericAndTextRowsSideBySide() {
        DatapointFrameWriter w = DatapointFrameWriter.forType(DatapointValueType.MIXED).series(1, "valve");
        w.addMixed(1, 1000, "open");
        w.addMixed(1, 2000, "3.5");
        w.addMixed(1, 3000, "closed");
        w.addMixed(1, 4000, "-1");
        DatapointFrame f = DatapointFrame.parseAll(w.build(ZSTD), ZSTD).get(0);
        assertEquals("open", f.valueAsString(0));
        assertEquals("3.5", f.valueAsString(1));
        assertEquals("closed", f.valueAsString(2));
        assertEquals("-1.0", f.valueAsString(3));
        assertEquals(2, f.batches().get(0).columns()[2].nullCount());
        assertEquals(2, f.batches().get(0).columns()[3].nullCount());
    }

    @Test
    void aBodyHoldsSeveralFramesOfDifferentTypes() {
        DatapointFrameWriter a = DatapointFrameWriter.forType(DatapointValueType.FLOAT).series(1, "a");
        a.addFloat(1, 1000, 1.5);
        DatapointFrameWriter b = DatapointFrameWriter.forType(DatapointValueType.TEXT).series(2, "b");
        b.addText(2, 1000, "x");
        b.addText(2, 2000, "y");
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.writeBytes(a.build(ZSTD));
        body.writeBytes(b.build(ZSTD));

        List<DatapointFrame> frames = DatapointFrame.parseEnvelopes(body.toByteArray());
        assertEquals(2, frames.size());
        assertEquals(DatapointValueType.FLOAT, frames.get(0).valueType());
        assertEquals(DatapointValueType.TEXT, frames.get(1).valueType());
        assertFalse(frames.get(1).decoded());
        frames.get(1).decode(ZSTD);
        assertEquals("y", frames.get(1).valueAsString(1));
        assertEquals(1, frames.get(1).index());
    }

    @Test
    void theEnvelopeIsLaidOutAsSpecified() {
        DatapointFrameWriter w = DatapointFrameWriter.forType(DatapointValueType.FLOAT32).series(5, "ab");
        w.addFloat32(5, 1000, 1f);
        byte[] frame = w.build(ZSTD);
        ByteBuffer buf = ByteBuffer.wrap(frame).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals('D', frame[0]);
        assertEquals('P', frame[3]);
        assertEquals(1, frame[4]);
        assertEquals(7, frame[5]);
        assertEquals(1, frame[6]);
        assertEquals(1, frame[7]);
        assertEquals(1, buf.getInt(8));
        assertEquals(1, buf.getInt(12));
        assertEquals(8 + 1 + 2, buf.getInt(16));
        int payloadLength = buf.getInt(20);
        int rawLength = buf.getInt(24);
        assertEquals(FrameLimits.HEADER_BYTES + 11 + payloadLength, frame.length);
        assertEquals(5, buf.getLong(28));
        assertEquals(2, frame[36]);
        assertEquals('a', frame[37]);
        assertTrue(rawLength > payloadLength || rawLength < 64, "a tiny stream may not compress");
    }

    @Test
    void theWriterRefusesWhatTheReaderWouldRefuse() {
        DatapointFrameWriter w = DatapointFrameWriter.forType(DatapointValueType.TEXT).series(1, "a");
        assertThrows(IllegalArgumentException.class, () -> w.addText(1, 1, ""));
        assertThrows(IllegalArgumentException.class, () -> w.addText(1, 1, "x".repeat(65)));
        assertThrows(IllegalArgumentException.class, () -> w.addText(1, 1, "æ".repeat(64) + "ø".repeat(64) + "å".repeat(64)));
        assertThrows(IllegalStateException.class, () -> w.addBigint(1, 1, 1));
        DatapointFrameWriter n = DatapointFrameWriter.forType(DatapointValueType.NUMERIC).series(1, "a");
        assertThrows(IllegalArgumentException.class, () -> n.addNumeric(1, 1, new BigDecimal("1000000000000")));
        DatapointFrameWriter noSeries = DatapointFrameWriter.forType(DatapointValueType.FLOAT);
        noSeries.addFloat(9, 1, 1d);
        assertThrows(IllegalStateException.class, () -> noSeries.build(ZSTD));
        DatapointFrameWriter empty = DatapointFrameWriter.forType(DatapointValueType.FLOAT).series(1, "a");
        assertThrows(IllegalStateException.class, () -> empty.build(ZSTD));
    }

    @Test
    void decimal32ClampsAreCounted() {
        DatapointFrameWriter w = DatapointFrameWriter.forType(DatapointValueType.DECIMAL32).series(1, "a");
        w.add(1, 1, "100000");
        w.add(1, 2, "-100000");
        w.add(1, 3, "1");
        assertEquals(2, w.clampedCount());
        DatapointFrame f = DatapointFrame.parseAll(w.build(ZSTD), ZSTD).get(0);
        assertEquals("99999.9999", f.valueAsString(0));
        assertEquals("-99999.9999", f.valueAsString(1));
    }

    @Test
    void estimatedSizeBoundsTheRealStream() {
        DatapointFrameWriter w = DatapointFrameWriter.forType(DatapointValueType.TEXT).series(1, "a");
        for (int i = 0; i < 500; i++) {
            w.addText(1, i, "value-" + i);
        }
        long estimate = w.estimatedRawBytes();
        DatapointFrame f = DatapointFrame.parseAll(w.build(ZSTD), ZSTD).get(0);
        assertTrue(f.rawLength() <= estimate, f.rawLength() + " > " + estimate);
        assertTrue(f.rawLength() > estimate - 600);
    }

    @Test
    void aFullFrameStaysUnderTheBrokerLimit() {
        // 100k float64 rows across 10k series: the worst numeric case of the caps.
        DatapointFrameWriter w = DatapointFrameWriter.forType(DatapointValueType.FLOAT);
        for (int s = 0; s < 10_000; s++) {
            w.series(s, "series_" + s);
            for (int p = 0; p < 10; p++) {
                w.addFloat(s, p, s + p / 10d);
            }
        }
        byte[] frame = w.build(ZSTD);
        assertTrue(frame.length < 4 * 1024 * 1024, "frame is " + frame.length + " bytes");
        DatapointFrame f = DatapointFrame.parseAll(frame, ZSTD).get(0);
        assertEquals(100_000, f.rowCount());
        assertEquals(10_000, f.seriesCount());
        assertNotNull(f.runs().get(9_999).externalId());
    }

    private static String roundTrip(DatapointValueType type, String value) {
        DatapointFrameWriter w = DatapointFrameWriter.forType(type).series(1, "s");
        w.add(1, 1000, value);
        return DatapointFrame.parseAll(w.build(ZSTD), ZSTD).get(0).valueAsString(0);
    }
}
