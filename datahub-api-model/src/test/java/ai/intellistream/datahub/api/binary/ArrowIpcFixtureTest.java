// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.api.binary;

import ai.intellistream.datahub.api.binary.ArrowIpc.Batch;
import ai.intellistream.datahub.api.binary.FrameFormatException.Reason;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Streams written by an Arrow implementation that is not ours. All three were produced by
 * ClickHouse 26.8.2.7 ({@code FORMAT ArrowStream}, no in-band compression) from six rows, so a
 * reader that accepts them accepts what the C++ library writes: a FlatBuffer layout our own writer
 * never produces, and a schema whose types must match the canon exactly.
 */
class ArrowIpcFixtureTest {

    /** {@code toFloat32(number / 10)}: a Float32 value column. */
    static final String FLOAT32_STREAM =
            "/////+gAAAAQAAAAAAAKAAwABgAFAAgACgAAAAABBAAMAAAACAAIAAAABAAIAAAABAAAAAMAAAB8AAAAMAAAAAQAAACc////AAAAAwgAAAAUAAAABQAAAHZhbHVlAAYACAAGAAYAAAAAAAEAxP///wAAAAoIAAAAHAAAAAkAAAB0aW1lc3RhbXAAAAAIAAwABgAIAAgAAAAAAAEABAAAAAMAAABVVEMADAAQAAgAAAAHAAwADAAAAAAAAAIIAAAAIAAAAA0AAAB0aW1lc2VyaWVzX2lkAAAACAAMAAgABwAIAAAAAAAAAUAAAAAAAAAA/////+gAAAAUAAAAAAAAAAwAFgAGAAUACAAMAAwAAAAAAwQAGAAAAHgAAAAAAAAAAAAKABgADAAEAAgACgAAAHwAAAAQAAAABgAAAAAAAAAAAAAABgAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAADAAAAAAAAAAMAAAAAAAAAAAAAAAAAAAADAAAAAAAAAAMAAAAAAAAABgAAAAAAAAAAAAAAAAAAAAYAAAAAAAAAAYAAAAAAAAAAAAAAADAAAABgAAAAAAAAAAAAAAAAAAAAYAAAAAAAAAAAAAAAAAAAAGAAAAAAAAAAAAAAAAAAAACwAAAAAAAAALAAAAAAAAAAsAAAAAAAAADAAAAAAAAAAMAAAAAAAAAAwAAAAAAAAAAGjlz4sBAADoa+XPiwEAANBv5c+LAQAAuHPlz4sBAACgd+XPiwEAAIh75c+LAQAAAAAAAM3MzD3NzEw+mpmZPs3MzD4AAAA//////wAAAAA=";

    /** {@code toFloat32(number) / 10}: ClickHouse promotes the division to Float64. */
    static final String FLOAT64_STREAM =
            "/////+gAAAAQAAAAAAAKAAwABgAFAAgACgAAAAABBAAMAAAACAAIAAAABAAIAAAABAAAAAMAAAB8AAAAMAAAAAQAAACc////AAAAAwgAAAAUAAAABQAAAHZhbHVlAAYACAAGAAYAAAAAAAIAxP///wAAAAoIAAAAHAAAAAkAAAB0aW1lc3RhbXAAAAAIAAwABgAIAAgAAAAAAAEABAAAAAMAAABVVEMADAAQAAgAAAAHAAwADAAAAAAAAAIIAAAAIAAAAA0AAAB0aW1lc2VyaWVzX2lkAAAACAAMAAgABwAIAAAAAAAAAUAAAAAAAAAA/////+gAAAAUAAAAAAAAAAwAFgAGAAUACAAMAAwAAAAAAwQAGAAAAJAAAAAAAAAAAAAKABgADAAEAAgACgAAAHwAAAAQAAAABgAAAAAAAAAAAAAABgAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAADAAAAAAAAAAMAAAAAAAAAAAAAAAAAAAADAAAAAAAAAAMAAAAAAAAABgAAAAAAAAAAAAAAAAAAAAYAAAAAAAAAAwAAAAAAAAAAAAAAADAAAABgAAAAAAAAAAAAAAAAAAAAYAAAAAAAAAAAAAAAAAAAAGAAAAAAAAAAAAAAAAAAAACwAAAAAAAAALAAAAAAAAAAsAAAAAAAAADAAAAAAAAAAMAAAAAAAAAAwAAAAAAAAAAGjlz4sBAADoa+XPiwEAANBv5c+LAQAAuHPlz4sBAACgd+XPiwEAAIh75c+LAQAAAAAAAAAAAACamZmZmZm5P5qZmZmZmck/MzMzMzMz0z+amZmZmZnZPwAAAAAAAOA//////wAAAAA=";

    /** {@code concat('v', toString(number))} with {@code output_format_arrow_string_as_string=1}. */
    static final String TEXT_STREAM =
            "/////+AAAAAQAAAAAAAKAAwABgAFAAgACgAAAAABBAAMAAAACAAIAAAABAAIAAAABAAAAAMAAAB4AAAALAAAAAQAAACg////AAAABQgAAAAUAAAABQAAAHZhbHVlAAAABAAEAAQAAADE////AAAACggAAAAcAAAACQAAAHRpbWVzdGFtcAAAAAgADAAGAAgACAAAAAAAAQAEAAAAAwAAAFVUQwAMABAACAAAAAcADAAMAAAAAAAAAggAAAAgAAAADQAAAHRpbWVzZXJpZXNfaWQAAAAIAAwACAAHAAgAAAAAAAABQAAAAP/////4AAAAFAAAAAAAAAAMABYABgAFAAgADAAMAAAAAAMEABgAAACQAAAAAAAAAAAACgAYAAwABAAIAAoAAACMAAAAEAAAAAYAAAAAAAAAAAAAAAcAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAwAAAAAAAAADAAAAAAAAAAAAAAAAAAAAAwAAAAAAAAADAAAAAAAAAAYAAAAAAAAAAAAAAAAAAAAGAAAAAAAAAAHAAAAAAAAACAAAAAAAAAAAwAAAAAAAAAAAAAAAMAAAAGAAAAAAAAAAAAAAAAAAAABgAAAAAAAAAAAAAAAAAAAAYAAAAAAAAAAAAAAAAAAAALAAAAAAAAAAsAAAAAAAAACwAAAAAAAAAMAAAAAAAAAAwAAAAAAAAADAAAAAAAAAAAaOXPiwEAAOhr5c+LAQAA0G/lz4sBAAC4c+XPiwEAAKB35c+LAQAAiHvlz4sBAAAAAAAAAgAAAAQAAAAGAAAACAAAAAoAAAAMAAAAAAAAAHYwdjF2MnYzdjR2NQAAAAD/////AAAAAA==";

    static final long[] IDS = {11, 11, 11, 12, 12, 12};

    @Test
    void readsAFloat32StreamWrittenByClickHouse() {
        byte[] stream = Base64.getDecoder().decode(FLOAT32_STREAM);
        List<Batch> batches = ArrowIpc.readStream(ArrowSchemaCanon.of(DatapointValueType.FLOAT32), stream, 0, 100_000);
        assertEquals(1, batches.size());
        Batch b = batches.get(0);
        assertEquals(6, b.rows());
        for (int r = 0; r < 6; r++) {
            assertEquals(IDS[r], b.columns()[0].data().getLong(r * 8));
            assertEquals(1_700_000_000_000L + r * 1000L, b.columns()[1].data().getLong(r * 8));
            assertEquals((float) (r / 10d), b.columns()[2].data().getFloat(r * 4));
        }
    }

    @Test
    void readsAFloat64StreamWrittenByClickHouse() {
        byte[] stream = Base64.getDecoder().decode(FLOAT64_STREAM);
        Batch b = ArrowIpc.readStream(ArrowSchemaCanon.of(DatapointValueType.FLOAT), stream, 0, 100_000).get(0);
        assertEquals(6, b.rows());
        for (int r = 0; r < 6; r++) {
            assertEquals(r / 10d, b.columns()[2].data().getDouble(r * 8));
        }
    }

    @Test
    void aStreamOfAnotherWidthIsASchemaMismatch() {
        byte[] stream = Base64.getDecoder().decode(FLOAT64_STREAM);
        FrameFormatException e = assertThrows(FrameFormatException.class,
                () -> ArrowIpc.readStream(ArrowSchemaCanon.of(DatapointValueType.FLOAT32), stream, 0, 100_000));
        assertEquals(Reason.SCHEMA_MISMATCH, e.reason());
    }

    @Test
    void readsATextStreamWrittenByClickHouse() {
        byte[] stream = Base64.getDecoder().decode(TEXT_STREAM);
        Batch b = ArrowIpc.readStream(ArrowSchemaCanon.of(DatapointValueType.TEXT), stream, 0, 10_000).get(0);
        assertEquals(6, b.rows());
        for (int r = 0; r < 6; r++) {
            assertEquals("v" + r, b.columns()[2].utf8At(r));
        }
    }

    @Test
    void aForeignStreamPassesTheWholeFrameValidator() {
        byte[] stream = Base64.getDecoder().decode(FLOAT32_STREAM);
        PayloadCodec zstd = new ZstdPayloadCodec(3);
        byte[] compressed = zstd.compress(stream);
        byte[] a = "sensor_a".getBytes(StandardCharsets.UTF_8);
        byte[] b = "sensor_b".getBytes(StandardCharsets.UTF_8);
        int dirLen = 2 * (8 + 1) + a.length + b.length;
        ByteBuffer f = ByteBuffer.allocate(FrameLimits.HEADER_BYTES + dirLen + compressed.length).order(ByteOrder.LITTLE_ENDIAN);
        f.put(FrameLimits.MAGIC).put((byte) 1).put((byte) DatapointValueType.FLOAT32.id()).put((byte) 1).put((byte) 1);
        f.putInt(6).putInt(2).putInt(dirLen).putInt(compressed.length).putInt(stream.length);
        f.putLong(11).put((byte) a.length).put(a);
        f.putLong(12).put((byte) b.length).put(b);
        f.put(compressed);

        DatapointFrame frame = DatapointFrame.parseAll(f.array(), zstd).get(0);
        assertEquals(6, frame.rowCount());
        assertArrayEquals(new long[]{11, 12}, frame.seriesIds());
        assertEquals(2, frame.runs().size());
        assertEquals("sensor_b", frame.runs().get(1).externalId());
        assertEquals("0.5", frame.valueAsString(5));
        assertNotNull(frame.batches());
    }

    @Test
    void ourSchemaMessageIsEncapsulatedLikeTheForeignOne() {
        // The bytes differ (FlatBuffers layout is writer-specific), the framing must not.
        byte[] stream = Base64.getDecoder().decode(FLOAT32_STREAM);
        byte[] ours = ArrowSchemaCanon.of(DatapointValueType.FLOAT32).schemaMessage();
        assertEquals(-1, ByteBuffer.wrap(stream).order(ByteOrder.LITTLE_ENDIAN).getInt(0));
        assertEquals(-1, ByteBuffer.wrap(ours).order(ByteOrder.LITTLE_ENDIAN).getInt(0));
        assertEquals(0, ByteBuffer.wrap(ours).order(ByteOrder.LITTLE_ENDIAN).getInt(4) % 8);
    }
}
