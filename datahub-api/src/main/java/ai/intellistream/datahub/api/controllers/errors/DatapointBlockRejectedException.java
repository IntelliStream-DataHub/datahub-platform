// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.controllers.errors;

import ai.intellistream.datahub.api.binary.FrameFormatException;
import org.springframework.http.HttpStatus;

import java.net.URI;
import java.util.List;
import java.util.Locale;

/**
 * A binary datapoint request the API refuses as a whole: nothing of it was published.
 *
 * <p>Each refusal has the problem type its status calls for, so one type never answers with two
 * statuses. Where the same condition already has a type elsewhere in the API it reuses it: an
 * oversized request is {@code request-too-large} and a {@code Content-Encoding} header is
 * {@code unsupported-media-type}, whichever path sent them. The reason stays beside the type as a
 * stable kebab-case sub-code: the SDKs match {@code unknown-timeseries} and
 * {@code external-id-mismatch} in the body, and a malformed frame has thirteen ways to be wrong.
 */
public class DatapointBlockRejectedException extends RuntimeException {

    private final HttpStatus status;
    private final URI type;
    private final String title;
    private final String reason;
    private final Integer frameIndex;
    private final List<Long> timeseriesIds;
    private final Integer retryAfterSeconds;

    private DatapointBlockRejectedException(HttpStatus status, URI type, String title, String reason,
                                            Integer frameIndex, List<Long> timeseriesIds,
                                            Integer retryAfterSeconds, String detail) {
        super(detail);
        this.status = status;
        this.type = type;
        this.title = title;
        this.reason = reason;
        this.frameIndex = frameIndex;
        this.timeseriesIds = timeseriesIds == null ? List.of() : List.copyOf(timeseriesIds);
        this.retryAfterSeconds = retryAfterSeconds;
    }

    /** A malformed or over-limit frame: 413 for the size caps, 400 for everything else. */
    public static DatapointBlockRejectedException from(FrameFormatException e) {
        String reason = kebab(e.reason().name());
        return switch (e.reason()) {
            case FRAME_TOO_LARGE, REQUEST_TOO_LARGE, TOO_MANY_FRAMES -> new DatapointBlockRejectedException(
                    HttpStatus.PAYLOAD_TOO_LARGE, Problems.type("request-too-large"), "Request body too large",
                    reason, e.frameIndex(), null, null, e.getMessage());
            default -> new DatapointBlockRejectedException(HttpStatus.BAD_REQUEST, Problems.INVALID_FRAME,
                    "Invalid frame", reason, e.frameIndex(), null, null, e.getMessage());
        };
    }

    public static DatapointBlockRejectedException unknownTimeseries(List<Long> ids) {
        return new DatapointBlockRejectedException(HttpStatus.NOT_FOUND, Problems.UNKNOWN_TIMESERIES,
                "Unknown timeseries", "unknown-timeseries", null, ids, null,
                ids.size() + " timeseries id(s) do not exist in this tenant.");
    }

    public static DatapointBlockRejectedException valueTypeMismatch(int frameIndex, List<Long> ids) {
        return new DatapointBlockRejectedException(HttpStatus.UNPROCESSABLE_CONTENT, Problems.VALUE_TYPE_MISMATCH,
                "Value type mismatch", "value-type-mismatch", frameIndex, ids, null,
                ids.size() + " series in frame " + frameIndex + " have a different value type than the frame declares.");
    }

    public static DatapointBlockRejectedException externalIdMismatch(int frameIndex, List<Long> ids) {
        return new DatapointBlockRejectedException(HttpStatus.UNPROCESSABLE_CONTENT, Problems.EXTERNAL_ID_MISMATCH,
                "External id mismatch", "external-id-mismatch", frameIndex, ids, null,
                ids.size() + " series in frame " + frameIndex + " have a different external id than the directory says; refresh the client's series cache.");
    }

    public static DatapointBlockRejectedException tooManyInFlight(int limit) {
        return new DatapointBlockRejectedException(HttpStatus.TOO_MANY_REQUESTS, Problems.TOO_MANY_IN_FLIGHT,
                "Too many requests in flight", "too-many-in-flight", null, null, 1,
                "This instance is validating " + limit + " binary requests already; retry shortly.");
    }

    public static DatapointBlockRejectedException unsupportedEncoding(String encoding) {
        return new DatapointBlockRejectedException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, Problems.UNSUPPORTED_MEDIA_TYPE,
                "Unsupported Media Type", "unsupported-content-encoding", null, null, null,
                "Content-Encoding " + encoding + " is not accepted: frames carry their own zstd compression.");
    }

    private static String kebab(String name) {
        return name.toLowerCase(Locale.ROOT).replace('_', '-');
    }

    public HttpStatus getStatus() {
        return status;
    }

    public URI getType() {
        return type;
    }

    public String getTitle() {
        return title;
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
