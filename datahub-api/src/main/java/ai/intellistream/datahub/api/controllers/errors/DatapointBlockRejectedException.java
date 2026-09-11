// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.controllers.errors;

import ai.intellistream.datahub.api.binary.FrameFormatException;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Locale;

/**
 * A binary datapoint request the API refuses as a whole: nothing of it was published. The reason
 * is a stable kebab-case token the SDKs branch on, the status says whether a retry can ever help.
 */
public class DatapointBlockRejectedException extends RuntimeException {

    private final HttpStatus status;
    private final String reason;
    private final Integer frameIndex;
    private final List<Long> timeseriesIds;
    private final Integer retryAfterSeconds;

    private DatapointBlockRejectedException(HttpStatus status, String reason, Integer frameIndex,
                                            List<Long> timeseriesIds, Integer retryAfterSeconds, String detail) {
        super(detail);
        this.status = status;
        this.reason = reason;
        this.frameIndex = frameIndex;
        this.timeseriesIds = timeseriesIds == null ? List.of() : List.copyOf(timeseriesIds);
        this.retryAfterSeconds = retryAfterSeconds;
    }

    /** A malformed or over-limit frame: 413 for the size caps, 400 for everything else. */
    public static DatapointBlockRejectedException from(FrameFormatException e) {
        HttpStatus status = switch (e.reason()) {
            case FRAME_TOO_LARGE, REQUEST_TOO_LARGE, TOO_MANY_FRAMES -> HttpStatus.PAYLOAD_TOO_LARGE;
            default -> HttpStatus.BAD_REQUEST;
        };
        return new DatapointBlockRejectedException(status, kebab(e.reason().name()), e.frameIndex(), null, null, e.getMessage());
    }

    public static DatapointBlockRejectedException unknownTimeseries(List<Long> ids) {
        return new DatapointBlockRejectedException(HttpStatus.NOT_FOUND, "unknown-timeseries", null, ids, null,
                ids.size() + " timeseries id(s) do not exist in this tenant.");
    }

    public static DatapointBlockRejectedException valueTypeMismatch(int frameIndex, List<Long> ids) {
        return new DatapointBlockRejectedException(HttpStatus.UNPROCESSABLE_ENTITY, "value-type-mismatch", frameIndex, ids, null,
                ids.size() + " series in frame " + frameIndex + " have a different value type than the frame declares.");
    }

    public static DatapointBlockRejectedException externalIdMismatch(int frameIndex, List<Long> ids) {
        return new DatapointBlockRejectedException(HttpStatus.UNPROCESSABLE_ENTITY, "external-id-mismatch", frameIndex, ids, null,
                ids.size() + " series in frame " + frameIndex + " have a different external id than the directory says; refresh the client's series cache.");
    }

    public static DatapointBlockRejectedException tooManyInFlight(int limit) {
        return new DatapointBlockRejectedException(HttpStatus.TOO_MANY_REQUESTS, "too-many-in-flight", null, null, 1,
                "This instance is validating " + limit + " binary requests already; retry shortly.");
    }

    public static DatapointBlockRejectedException unsupportedEncoding(String encoding) {
        return new DatapointBlockRejectedException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "unsupported-content-encoding", null, null, null,
                "Content-Encoding " + encoding + " is not accepted: frames carry their own zstd compression.");
    }

    public static DatapointBlockRejectedException bodyTooLarge(long limitBytes) {
        return new DatapointBlockRejectedException(HttpStatus.PAYLOAD_TOO_LARGE, "request-too-large", null, null, null,
                "The body is larger than " + limitBytes + " bytes; split it into more requests.");
    }

    private static String kebab(String name) {
        return name.toLowerCase(Locale.ROOT).replace('_', '-');
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getReason() {
        return reason;
    }

    public Integer getFrameIndex() {
        return frameIndex;
    }

    public List<Long> getTimeseriesIds() {
        return timeseriesIds;
    }

    public Integer getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
