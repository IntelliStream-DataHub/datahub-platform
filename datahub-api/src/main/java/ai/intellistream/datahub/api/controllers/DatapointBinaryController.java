// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.controllers;

import ai.intellistream.datahub.api.binary.FrameLimits;
import ai.intellistream.datahub.api.config.LimitsProperties;
import ai.intellistream.datahub.api.controllers.errors.DatapointBlockRejectedException;
import ai.intellistream.datahub.api.services.DatapointBinaryIngestService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.InputStream;

/**
 * The binary datapoint insert: its own endpoint and code path next to the JSON
 * {@code POST /timeseries/data}, sharing nothing with it but the quotas, the ACL and the
 * latest-value cache. The body is one or more frames; {@link FrameLimits} and
 * {@link ai.intellistream.datahub.api.binary.ArrowSchemaCanon} are the normative caps and
 * schemas, and the byte-level spec for other producers is in the SDK documentation.
 */
@RestController
@RequestMapping("/timeseries/data")
@Tag(name = "Time-series")
@Slf4j
public class DatapointBinaryController {

    private final DatapointBinaryIngestService service;
    private final LimitsProperties limits;

    public DatapointBinaryController(DatapointBinaryIngestService service, LimitsProperties limits) {
        this.service = service;
        this.limits = limits;
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

                    ### Limits
                    - 100 000 points per numeric frame, 10 000 per text or mixed frame, 10 000 series per frame
                    - 4 MiB per frame and 64 MiB per request, decompressed; 32 frames per request
                    - the request body itself is capped by `datahub.limits.max-body-bytes-datapoints-binary`
                    - a per-instance number of requests validated at once; over it is a **429** with `Retry-After`

                    Series are named by their internal id and must exist, belong to a dataset the caller may
                    write, and have the value type the frame declares. The directory's external ids must
                    match, which is how a stale client-side series cache is detected.
                    """)
    @ApiResponse(responseCode = "204", description = "Every frame was accepted and published.")
    @ApiResponse(responseCode = "400", description = "A frame is malformed, unsorted, uncompressed or fails the schema; the `reason` says which.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE))
    @ApiResponse(responseCode = "403", description = "The caller may not write one of the series' datasets.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE))
    @ApiResponse(responseCode = "404", description = "A series id does not exist; `timeseriesIds` lists them.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE))
    @ApiResponse(responseCode = "413", description = "Over a size cap: the body, a frame, the request's decompressed total or the frame count.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE))
    @ApiResponse(responseCode = "415", description = "Wrong media type, or a `Content-Encoding` header.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE))
    @ApiResponse(responseCode = "422", description = "A series has another value type or external id than the frame claims; `timeseriesIds` lists them.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE))
    @ApiResponse(responseCode = "429", description = "Too many binary requests in flight on this instance, or a quota; retry after `Retry-After`.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE))
    @PostMapping(path = "/binary", consumes = FrameLimits.MEDIA_TYPE)
    public ResponseEntity<Void> insertBinary(HttpServletRequest request) throws IOException {
        String encoding = request.getHeader(HttpHeaders.CONTENT_ENCODING);
        if (encoding != null && !encoding.isBlank() && !"identity".equalsIgnoreCase(encoding.trim())) {
            throw DatapointBlockRejectedException.unsupportedEncoding(encoding.trim());
        }
        long cap = limits.getMaxBodyBytesDatapointsBinary();
        byte[] body;
        try (InputStream in = request.getInputStream()) {
            body = in.readNBytes((int) Math.min(cap + 1, Integer.MAX_VALUE));
        }
        if (body.length > cap) {
            throw DatapointBlockRejectedException.bodyTooLarge(cap);
        }
        DatapointBinaryIngestService.Summary summary = service.ingest(body, request.getContentLengthLong());
        log.debug("Binary datapoint request accepted: {} frames, {} rows, {} series", summary.frames(), summary.rows(), summary.series());
        return ResponseEntity.noContent().build();
    }
}
