package ai.intellistream.datahub.api.controllers;

import ai.intellistream.datahub.api.controllers.errors.BadRequestError;
import ai.intellistream.datahub.api.controllers.errors.BadRequestException;
import ai.intellistream.datahub.api.graphtransfer.GraphFileCodec;
import ai.intellistream.datahub.api.graphtransfer.GraphImportResult;
import ai.intellistream.datahub.api.graphtransfer.GraphTransferLimitException;
import ai.intellistream.datahub.api.graphtransfer.InvalidGraphFileException;
import ai.intellistream.datahub.api.policy.NamingPolicyViolationException;
import ai.intellistream.datahub.api.services.GraphTransferService;
import ai.intellistream.datahub.errors.ResponseError;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.InputStream;

/**
 * Export / import of a resource graph component as a portable binary file. Split out of
 * {@link ResourceController} to keep the transfer concerns (binary streaming, gzip, file
 * semantics) away from the JSON CRUD endpoints.
 */
@RestController
@RequestMapping("/resources")
@Slf4j
public class GraphTransferController {

    private final GraphTransferService graphTransferService;

    public GraphTransferController(GraphTransferService graphTransferService) {
        this.graphTransferService = graphTransferService;
    }

    @Tag(name = "Resources")
    @Operation(
            summary = "Export a resource's whole graph component as a file",
            description = """
                    Starting from one resource (usually a root), walk the entire connected
                    component in the graph — every reachable resource plus every relationship
                    between them — and return it as a downloadable binary file (gzip-compressed).

                    The file references everything by `externalId`, never by numeric id, so it can
                    be imported into another tenant or environment with `POST /resources/import`.
                    Node metadata and geometry are included from the system of record.

                    Components larger than 2,000,000 nodes or 2,000,000 relationships are rejected
                    with `400` rather than exported partially.
                    """
    )
    @ApiResponse(responseCode = "200", description = "The exported graph file (gzip-compressed binary).",
            content = @Content(
                    mediaType = MediaType.APPLICATION_OCTET_STREAM_VALUE,
                    schema = @Schema(type = "string", format = "binary")
            ))
    @ApiResponse(responseCode = "404", description =
            "The starting resource was not found. Check `id` and your tenant.",
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(type = "string", example = "Could not find resource with id: 42")
            ))
    @ApiResponse(responseCode = "400", description =
            "The component is over the export limit (2,000,000 nodes / 2,000,000 relationships). "
                    + "Nothing is exported partially.",
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = BadRequestError.class)
            ))
    @GetMapping(value = "/export/{id}", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ResponseEntity<?> export(
            HttpServletResponse res,
            @Parameter(description = "Numeric id of the resource to export from.", example = "5677892")
            @PathVariable("id") Long id) {
        GraphTransferService.PreparedExport prepared;
        try {
            // Every failure happens here, before the response commits: not-found and no-read-access
            // surface as ObjectNotFoundException (mapped to 404 by ObjectNotFoundExceptionHandler,
            // same as the other resource read endpoints), an oversized component as 400 below.
            prepared = graphTransferService.prepareExport(id);
        } catch (GraphTransferLimitException e) {
            return new ResponseEntity<>(badRequest(e.getMessage()), HttpStatus.BAD_REQUEST);
        }

        res.setContentType(MediaType.APPLICATION_OCTET_STREAM_VALUE);
        res.setHeader(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + prepared.fileName() + "\"");
        try {
            // Encoded and gzipped straight onto the response, item by item — the file is never
            // buffered whole. The servlet stream is the container's to close (see FileController).
            graphTransferService.writeExport(prepared, res.getOutputStream());
            return new ResponseEntity<>(HttpStatus.OK);
        } catch (IOException e) {
            // Almost always the client cancelling the download; the response is committed, so
            // there is no status left to change either way.
            log.debug("Graph export stream ended early: {}", e.getMessage());
            return null;
        }
    }

    @Tag(name = "Resources")
    @Operation(
            summary = "Import a previously exported graph file",
            description = """
                    Recreate the resources and relationships from a file produced by
                    `GET /resources/export/{id}`. Send the file verbatim as the request body
                    (`application/octet-stream`).

                    Resources that already exist (matched by `externalId`) are skipped rather than
                    rejected, so importing a file back into the tenant it came from is a no-op.
                    Timeseries cannot be created through the resource api; missing ones are listed
                    in the response and their relationships skipped.

                    ### Streaming, in segments
                    The upload is processed as it streams in: every 50,000 objects commit as one
                    transaction (a 2M-object file is ~40 segments), so arbitrarily large files
                    never build one enormous transaction and memory stays flat. Each segment is
                    atomic; a failure keeps the segments already committed and rejects the rest.
                    Because import skips what already exists — nodes by `externalId`, relationships
                    by (from, to, type) — simply re-upload the same file after a failure: it
                    fast-forwards through the committed segments and resumes where it stopped.
                    The response reports how many segments were committed.

                    Limits: at most 2,000,000 nodes and 2,000,000 relationships per file, and
                    the upload may not exceed 512 MB. Files over a limit are rejected with `413`.
                    The upload is exempt from the general request-body cap, since it is consumed
                    as it arrives rather than buffered.
                    """
    )
    @ApiResponse(responseCode = "200", description = "Summary of what was created and what was skipped.",
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = GraphImportResult.class)
            ))
    @ApiResponse(responseCode = "400", description =
            "The body is not a readable graph export file, or its content failed validation.",
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = BadRequestError.class)
            ))
    @ApiResponse(responseCode = "413", description =
            "The file is over a transfer limit: larger than 512 MB, or more than 2,000,000 nodes "
                    + "or 2,000,000 relationships. Nothing is imported.",
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = BadRequestError.class)
            ))
    @PostMapping(value = "/import",
            consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> importGraph(HttpServletRequest request) {
        // Cheap early rejection when the client declares its size. The streaming cap inside the
        // service still guards chunked uploads and lying Content-Length headers.
        long declared = request.getContentLengthLong();
        if (declared > GraphFileCodec.MAX_COMPRESSED_BYTES) {
            return new ResponseEntity<>(payloadTooLarge(
                    "The file is larger than " + (GraphFileCodec.MAX_COMPRESSED_BYTES / (1024 * 1024)) + " MB."),
                    HttpStatus.PAYLOAD_TOO_LARGE);
        }
        try (InputStream in = request.getInputStream()) {
            GraphImportResult result = graphTransferService.importGraph(in);
            return new ResponseEntity<>(result, HttpStatus.OK);
        } catch (GraphTransferLimitException e) {
            return new ResponseEntity<>(payloadTooLarge(e.getMessage()), HttpStatus.PAYLOAD_TOO_LARGE);
        } catch (InvalidGraphFileException e) {
            return new ResponseEntity<>(badRequest(e.getMessage()), HttpStatus.BAD_REQUEST);
        } catch (ConstraintViolationException e) {
            return new ResponseEntity<>(badRequest(e.getMessage()), HttpStatus.BAD_REQUEST);
        } catch (NamingPolicyViolationException e) {
            // Let it reach NamingPolicyExceptionHandler as an RFC 9457 problem response with the
            // per-item violations list, same as /resources/create.
            throw e;
        } catch (BadRequestException e) {
            var error = e.getError();
            return new ResponseEntity<>(error, HttpStatus.valueOf(error.getError().getCode()));
        }
        // Let dataset-ACL denials surface as 403 instead of being masked as 500 below.
        catch (org.springframework.security.access.AccessDeniedException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            log.error(e.getMessage(), e);
            return ResponseEntity.internalServerError().build();
        }
    }

    private static ResponseError<BadRequestError> payloadTooLarge(String message) {
        var error = new BadRequestError();
        error.setCode(413);
        error.setMessage(message);
        var response = new ResponseError<BadRequestError>();
        response.setError(error);
        return response;
    }

    private static ResponseError<BadRequestError> badRequest(String message) {
        var error = new BadRequestError();
        error.setCode(400);
        error.setMessage(message);
        var response = new ResponseError<BadRequestError>();
        response.setError(error);
        return response;
    }
}
