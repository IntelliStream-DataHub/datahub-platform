// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.api.binary;

import ai.intellistream.datahub.api.binary.ArrowIpc.RawBatch;
import ai.intellistream.datahub.api.binary.ArrowIpc.StreamReader;
import ai.intellistream.datahub.api.binary.FrameFormatException.Reason;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;

/**
 * Turns the frames of one value type into the one Arrow stream the consumer inserts, relaying
 * their record batches as they came. A {@link FrameLimits#COMPRESSION_ARROW_ZSTD} frame's batches
 * keep the buffers the client compressed, so a point the SDK compressed in-band stays compressed
 * until ClickHouse itself decodes it; a {@link FrameLimits#COMPRESSION_ZSTD} frame's payload is
 * decompressed once and its batches relayed as they are, uncompressed. Nothing is copied column by
 * column: the batches arrive as the frames held them and ClickHouse squashes small blocks itself.
 *
 * <p>Two steps so that a batch of messages can relay each frame on its own, and settle each
 * message on its own: {@link #batches} per frame, then {@link #stream} per tenant and value type.
 *
 * @see ArrowIpc why this package handles Arrow itself
 */
public final class FrameMerger {

    private static final byte[] END_OF_STREAM = {(byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, 0, 0, 0, 0};

    private FrameMerger() {
    }

    /**
     * The frame's record batch messages as they were sent, header-checked but not decoded; a
     * {@link FrameLimits#COMPRESSION_ZSTD} payload is decompressed by {@code codec} first. A
     * frame that is not well formed throws a {@link FrameFormatException} naming it.
     */
    public static byte[] batches(DatapointFrame frame, PayloadCodec codec) {
        ArrowSchemaCanon canon = ArrowSchemaCanon.of(frame.valueType());
        byte[] payload;
        if (frame.compression() == FrameLimits.COMPRESSION_ARROW_ZSTD) {
            payload = frame.payloadBytes();
        } else {
            try {
                payload = codec.decompress(frame.payloadBytes(), frame.rawLength());
            } catch (IllegalArgumentException e) {
                throw new FrameFormatException(Reason.PAYLOAD_INVALID, frame.index(), e.getMessage());
            }
        }
        // A record batch body is no longer than the payload as sent, nor, inflated, than the
        // envelope's raw length; a tiny batch's in-band form can be the longer of the two.
        long maxBodyBytes = Math.max(frame.payloadLength(), frame.rawLength());
        StreamReader reader = new StreamReader(canon, new ByteArrayInputStream(payload), frame.index(),
                FrameLimits.maxRows(frame.valueType()), maxBodyBytes, codec);
        ByteArrayOutputStream out = new ByteArrayOutputStream(payload.length);
        try {
            for (RawBatch raw = reader.nextRaw(); raw != null; raw = reader.nextRaw()) {
                raw.writeTo(out);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }

    /** One stream of the type's canon: its schema, the {@link #batches} of every frame in order, the end marker. */
    public static byte[] stream(DatapointValueType type, List<byte[]> batches) {
        ArrowSchemaCanon canon = ArrowSchemaCanon.of(type);
        int length = canon.schemaMessageLength() + END_OF_STREAM.length;
        for (byte[] b : batches) {
            length += b.length;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(length);
        canon.writeSchemaMessage(out);
        for (byte[] b : batches) {
            out.writeBytes(b);
        }
        out.writeBytes(END_OF_STREAM);
        return out.toByteArray();
    }
}
