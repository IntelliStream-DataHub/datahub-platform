// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.api.binary;

import ai.intellistream.datahub.models.validation.FieldLimits;

/**
 * The fixed numbers of the datapoint frame contract, version 1. Every cap here is mirrored in the
 * SDK documentation; the row caps reuse {@link FieldLimits} so the binary and JSON paths agree.
 */
public final class FrameLimits {

    private FrameLimits() {
    }

    public static final String MEDIA_TYPE = "application/vnd.intellistream.datapoint-block";

    public static final byte[] MAGIC = {'D', 'H', 'D', 'P'};
    public static final int VERSION = 1;
    public static final int CODEC_ARROW_IPC = 1;
    public static final int COMPRESSION_ZSTD = 1;

    /** Bytes before the series directory. */
    public static final int HEADER_BYTES = 28;

    /** A frame is one Pulsar message; this keeps it well under the broker's 5 MiB default. */
    public static final int MAX_FRAME_RAW_BYTES = 4 * 1024 * 1024;
    public static final int MAX_FRAMES_PER_REQUEST = 32;
    public static final long MAX_REQUEST_RAW_BYTES = 64L * 1024 * 1024;

    public static final int MAX_SERIES_PER_FRAME = FieldLimits.BATCH_ITEMS_MAX;
    /** Timeseries external ids are at most 256 characters, so at most this many UTF-8 bytes. */
    public static final int MAX_EXTERNAL_ID_BYTES = 1024;

    public static final int MAX_VALUE_CHARS = FieldLimits.DATAPOINT_VALUE_MAX;
    public static final int MAX_VALUE_BYTES = 256;

    /** Below this the server's per-block cost dominates; the SDKs merge smaller tails. */
    public static final int MIN_ROWS_PER_FRAME_HINT = 1_000;

    /** Rows per frame: the JSON path's per-collection caps, numeric and text alike. */
    public static int maxRows(DatapointValueType type) {
        return type.carriesText()
                ? FieldLimits.TEXT_DATAPOINTS_PER_COLLECTION_MAX
                : FieldLimits.DATAPOINTS_PER_COLLECTION_MAX;
    }

    /** Decimal(18, 6): the unscaled value must stay within 18 digits. */
    public static final long NUMERIC_UNSCALED_MAX = 999_999_999_999_999_999L;
    /** Decimal(9, 4): the unscaled value must stay within 9 digits, which is 99999.9999. */
    public static final int DECIMAL32_UNSCALED_MAX = 999_999_999;
}
