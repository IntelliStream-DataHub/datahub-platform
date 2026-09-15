// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.api.binary;

/**
 * A frame the reader refuses. The reason is stable and machine-readable so the API can put it in
 * its problem response and the SDK can tell a bug in its own writer from a limit.
 */
public class FrameFormatException extends RuntimeException {

    public enum Reason {
        MALFORMED_FRAME,
        UNSUPPORTED_VERSION,
        UNSUPPORTED_CODEC,
        UNCOMPRESSED_FRAME,
        UNKNOWN_VALUE_TYPE,
        FRAME_TOO_LARGE,
        TOO_MANY_FRAMES,
        REQUEST_TOO_LARGE,
        TRAILING_BYTES,
        DIRECTORY_INVALID,
        PAYLOAD_INVALID,
        SCHEMA_MISMATCH,
        ROW_COUNT_MISMATCH,
        UNSORTED,
        DUPLICATE_ROW,
        VALUE_INVALID
    }

    private final Reason reason;
    private final int frameIndex;

    public FrameFormatException(Reason reason, int frameIndex, String detail) {
        super(reason + " in frame " + frameIndex + ": " + detail);
        this.reason = reason;
        this.frameIndex = frameIndex;
    }

    public Reason reason() {
        return reason;
    }

    public int frameIndex() {
        return frameIndex;
    }
}
