// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.controllers;

import ai.intellistream.datahub.api.binary.FrameLimits;
import ai.intellistream.datahub.api.controllers.errors.DatapointBlockRejectedException;
import ai.intellistream.datahub.api.controllers.errors.schema.ApiProblem;
import ai.intellistream.datahub.api.controllers.errors.schema.DatapointBlockProblem;
import ai.intellistream.datahub.api.responses.DataRetriever;
import ai.intellistream.datahub.api.services.DatapointBinaryIngestService;
import ai.intellistream.datahub.api.services.TimeseriesService;
import ai.intellistream.datahub.models.forms.RetrieveFilter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;

/**
 * The binary datapoint insert and read: their own endpoints and code paths next to the JSON
 * {@code POST /timeseries/data} and {@code /timeseries/data/list}. The insert shares nothing with
 * its JSON twin but the quotas, the ACL and the latest-value cache; the read shares the request
 * body and the ACL. Both bodies are frames; {@link FrameLimits} and
 * {@link ai.intellistream.datahub.api.binary.ArrowSchemaCanon} are the normative caps and
 * schemas, and the byte-level spec for other producers is in the SDK documentation.
 */
@RestController
@RequestMapping("/timeseries/data")
@Tag(name = "Time-series")
@Slf4j
public class DatapointBinaryController {

    private final DatapointBinaryIngestService service;
    private final TimeseriesService timeseriesService;

    public DatapointBinaryController(DatapointBinaryIngestService service, TimeseriesService timeseriesService) {
        this.service = service;
        this.timeseriesService = timeseriesService;
    }

    @Operation(
            summary = "Insert data-points as binary frames",
            description = """
                    The high-throughput form of `POST /timeseries/data`, for the SDKs. The body is one or
                    more **datapoint frames**: a 28-byte envelope, a directory of the series in the frame,
                    and a zstd-compressed Arrow IPC stream in the canonical schema of the frame's value
                    type, rows sorted by series id and timestamp. The SDKs build frames; the byte layout is
                    documented for other producers.

                    Every frame in the request is validated before any is published, so a rejected request
                    inserts nothing and can be retried as a whole. Compression is mandatory: a frame that
                    declares none is refused, and no body-level `Content-Encoding` is accepted.

                    Publishing is not atomic: frames reach the message broker one at a time, so a **5xx**
                    can follow after some of them were published. Retrying the whole request is still
                    safe, since a datapoint is stored once per series and timestamp however often it is
                    sent, and the datapoint quotas count only the frames that were published.

                    ### Limits
                    - 100 000 points per numeric frame, 10 000 per text or mixed frame, 10 000 series per frame
                    - 4 MiB per frame and 64 MiB per request, decompressed; 32 frames per request
                    - 4.75 MiB (4 980 736 bytes) per frame as sent: envelope, directory and compressed payload together
                    - the request body itself is capped by `datahub.limits.max-body-bytes-datapoints-binary`
                    - a per-instance number of requests validated at once; over it is a **429** with `Retry-After`

                    Series are named by their internal id and must exist, belong to a dataset the caller may
                    write, and have the value type the frame declares. The directory's external ids must
                    match, which is how a stale client-side series cache is detected.
                    """)
    @ApiResponse(responseCode = "204", description = "Every frame was accepted and published.")
    @ApiResponse(responseCode = "400", description = "A frame is malformed, unsorted, uncompressed or fails the schema; the `reason` says which.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = DatapointBlockProblem.class)))
    @ApiResponse(responseCode = "403", description = "The caller may not write one of the series' datasets.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ApiProblem.class)))
    @ApiResponse(responseCode = "404", description = "A series id does not exist; `timeseriesIds` lists them.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = DatapointBlockProblem.class)))
    @ApiResponse(responseCode = "413", description = "Over a size cap: the body, a frame, the request's decompressed total or the frame count.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = DatapointBlockProblem.class)))
    @ApiResponse(responseCode = "415", description = "Wrong media type, or a `Content-Encoding` header.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = DatapointBlockProblem.class)))
    @ApiResponse(responseCode = "422", description = "A series has another value type or external id than the frame claims; `timeseriesIds` lists them.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = DatapointBlockProblem.class)))
    @ApiResponse(responseCode = "429", description = "Too many binary requests in flight on this instance, or a quota; retry after `Retry-After`.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = DatapointBlockProblem.class)))
    @ApiResponse(responseCode = "500", description = "Publishing failed partway; some frames may be stored. Retrying the whole request is safe.")
    @PostMapping(path = "/binary", consumes = FrameLimits.MEDIA_TYPE)
    public ResponseEntity<Void> insertBinary(HttpServletRequest request) throws IOException {
        String encoding = request.getHeader(HttpHeaders.CONTENT_ENCODING);
        if (encoding != null && !encoding.isBlank() && !"identity".equalsIgnoreCase(encoding.trim())) {
            throw DatapointBlockRejectedException.unsupportedEncoding(encoding.trim());
        }
        // The body cap is RequestBodySizeLimitFilter's: it counts the stream as it is read and answers
        // a body that runs over with a 413.
        DatapointBinaryIngestService.Summary summary;
        try (InputStream body = request.getInputStream()) {
            summary = service.ingest(body, request.getContentLengthLong());
        }
        log.debug("Binary datapoint request accepted: {} frames, {} rows, {} series", summary.frames(), summary.rows(), summary.series());
        return ResponseEntity.noContent().build();
    }

    @Operation(
            summary = "Retrieve data-points as binary frames",
            description = """
                    The high-throughput form of `POST /timeseries/data/list`, for the SDKs. The request is
                    the same JSON body; the answer is a sequence of **datapoint frames**, the format
                    `POST /timeseries/data/binary` takes, concatenated with nothing between them. The SDKs
                    read them; the byte layout is documented for other consumers. Ask for
                    `Accept: application/vnd.intellistream.datapoint-block, application/problem+json`,
                    so that a refusal can come back as the problem document it is.

                    Frames come in the order of `items`, and one series' rows may span several
                    consecutive frames. A series with no points in its window sends no frame, so an
                    empty body is a valid answer. Rows are sorted by timestamp, oldest first, and each
                    series returns at most its `limit`, from `start` on. There is no cursor: to read
                    further, send the request again with `start` one millisecond past the last
                    timestamp you received.

                    An item with `aggregates` and a `granularity` answers with aggregate frames (codec 2)
                    instead: one row per bucket that holds a point, carrying the bucket start and its
                    time-weighted `avg`, `min`, `max` and `sum` as Float64. All four come back whichever
                    `aggregates` named. Text series have no aggregates.

                    The body always ends with the 4-byte marker `DHDE`, after the last frame. The
                    response is streamed, so a failure after the first bytes have gone out can only end
                    the body early: a body without the marker was cut short, whether inside a frame or
                    between two, and the request can be sent again.

                    ### Limits
                    - 1 000 000 rows per request, all series together, or the request's own `limit` if
                      it is lower. An item without a `limit` gets an even share of what the others
                      leave, at most 100 000 rows.
                    - 100 000 values per numeric frame, 10 000 per text or mixed frame, 50 000 buckets
                      per aggregate frame.
                    """)
    @ApiResponse(responseCode = "200", description = "Zero or more frames, then the end marker.",
            content = @Content(mediaType = FrameLimits.MEDIA_TYPE))
    @ApiResponse(responseCode = "400", description = "The filter is invalid, the limits exceed the budget, a `cursor` was sent, "
            + "a text series was asked for aggregates, or a named series does not exist or is in a dataset the caller cannot read "
            + "(unless `ignoreUnknownIds`).",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ApiProblem.class)))
    @PostMapping(path = "/list/binary", consumes = MediaType.APPLICATION_JSON_VALUE, produces = FrameLimits.MEDIA_TYPE)
    public void retrieveBinary(@RequestBody DataRetriever<RetrieveFilter> request, HttpServletResponse response) throws IOException {
        List<TimeseriesService.BinaryRead> reads = timeseriesService.planBinaryRead(request);
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType(FrameLimits.MEDIA_TYPE);
        // Not closed here on failure: closing commits whatever is buffered as a complete answer.
        OutputStream out = response.getOutputStream();
        try {
            timeseriesService.writeFrames(reads, out);
        } catch (IOException | RuntimeException e) {
            // Nothing sent yet: drop the buffered frames and the binary content type so the failure
            // goes out as an ordinary problem response. Once committed, the missing end marker is
            // what tells the reader.
            if (!response.isCommitted()) {
                response.reset();
            }
            throw e;
        }
        out.close();
    }
}
