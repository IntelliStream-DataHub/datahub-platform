// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.controllers;

import ai.intellistream.datahub.api.datasecurity.DataSecurity;
import ai.intellistream.datahub.api.responses.DataWrapper;
import ai.intellistream.datahub.api.responses.swaggerdto.FileDataWrapper;
import ai.intellistream.datahub.api.responses.swaggerdto.IdCollectionDataWrapper;
import ai.intellistream.datahub.config.FilesConfig;
import ai.intellistream.datahub.helpers.text.TextValidator;
import ai.intellistream.datahub.helpers.utils.HttpHelper;
import ai.intellistream.datahub.helpers.utils.IdGenerator;
import ai.intellistream.datahub.tenant.TenantConfigService;
import ai.intellistream.datahub.tenant.TenantContext;
import ai.intellistream.datahub.jpa.domains.INode;
import ai.intellistream.datahub.jpa.dto.INodeDownload;
import ai.intellistream.datahub.models.IdCollection;
import ai.intellistream.datahub.models.files.FileUpdate;
import ai.intellistream.datahub.models.files.IndexNode;
import ai.intellistream.datahub.repositories.files.INodeRepository;
import ai.intellistream.datahub.services.DirectoryService;
import ai.intellistream.datahub.services.FileSystemService;
import ai.intellistream.datahub.transformers.FileTransformer;
import com.nimbusds.jose.shaded.gson.JsonIOException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import jakarta.validation.constraints.NotNull;
import lombok.extern.slf4j.Slf4j;
import ai.intellistream.datahub.api.config.UploadProperties;
import ai.intellistream.datahub.helpers.checksum.ChecksumFactory;
import ai.intellistream.datahub.helpers.checksum.FileChecksum;
import net.openhft.hashing.LongHashFunction;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/files")
@Slf4j
@Tag(name = "Files", description = """
        Upload, list, download and delete files organised into folders. Useful for
        attaching documents (manuals, reports, photos) to your resources. The files
        feature must be enabled for your tenant; calls return `403 Forbidden` if it isn't.""")
public class FileController {

    private static final String FILES_FEATURE_DISABLED = "Files feature is not enabled for this organization.";
    /** Default result cap, matching the default `limit` on the four POST searches. */
    private static final int SEARCH_LIMIT = 100;
    /** Ceiling a caller may raise the cap to, matching the {@code @Max(1000)} those four carry. */
    private static final int MAX_SEARCH_LIMIT = 1000;

    private final FileTransformer fileTransformer;

    private final INodeRepository iNodeRepository;

    private final FilesConfig filesConfig;

    private final Validator validator;

    private final FileSystemService fileSystemService;

    private final HttpHelper httpHelper;

    private final JsonMapper jsonMapper;

    private final TenantConfigService tenantConfigService;

    private final DataSecurity dataSecurity;

    private final ChecksumFactory checksumFactory;

    private final DirectoryService directoryService;

    private final UploadProperties uploadProperties;

    public FileController(
            FileTransformer fileTransformer,
            INodeRepository iNodeRepository,
            FilesConfig filesConfig,
            Validator validator,
            FileSystemService fileSystemService,
            HttpHelper httpHelper,
            JsonMapper jsonMapper,
            TenantConfigService tenantConfigService,
            DataSecurity dataSecurity,
            ChecksumFactory checksumFactory,
            DirectoryService directoryService,
            UploadProperties uploadProperties
    ) {
        this.fileTransformer = fileTransformer;
        this.iNodeRepository = iNodeRepository;
        this.filesConfig = filesConfig;
        this.validator = validator;
        this.fileSystemService = fileSystemService;
        this.httpHelper = httpHelper;
        this.jsonMapper = jsonMapper;
        this.tenantConfigService = tenantConfigService;
        this.dataSecurity = dataSecurity;
        this.checksumFactory = checksumFactory;
        this.directoryService = directoryService;
        this.uploadProperties = uploadProperties;
    }

    private boolean isFilesDisabled() {
        var tenant = tenantConfigService.getConfig(TenantContext.getTenantId());
        return !tenant.getFeatures().isFilesEnabled();
    }

    @Tag(name = "Files")
    @Operation(summary = "Upload file",
            description = "Upload a file with an HTTP PUT to /files. The file content is the raw " +
                    "request body; all metadata travels in request headers, which means the server " +
                    "validates and authorises the upload before it reads a single body byte. " +
                    "Headers: 'X-Datahub-Path' (required, the full destination path including the " +
                    "filename, each path segment percent-encoded), 'X-Datahub-External-Id' " +
                    "(optional, percent-encoded; defaults to the filename and is always " +
                    "sanitised to a lowercase slug), 'X-Datahub-Dataset-Id' " +
                    "(optional, dataset id for access control), and the optional, percent-encoded " +
                    "'X-Datahub-Description', 'X-Datahub-Source', 'X-Datahub-Source-Date-Created', " +
                    "'X-Datahub-Source-Last-Updated' (the two source dates are ISO-8601 with a zone " +
                    "or offset, e.g. 2026-06-24T12:00:00Z, or epoch millis as a fallback), " +
                    "'X-Datahub-Metadata' (a JSON object) and 'X-Datahub-Related-Resources' (a JSON " +
                    "array of resource ids). 'Content-Type' is the file's MIME type; omit it or send " +
                    "'application/octet-stream' to have the server auto-detect it.",
            requestBody = @RequestBody(content = @Content(mediaType = "application/octet-stream",
                    schema = @Schema(type = "string", format = "binary")
            ))
    )
    @ApiResponse(responseCode = "200", description = "The uploaded file.",
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = FileDataWrapper.class)
            ))
    @ApiResponse(responseCode = "400", description = "Invalid upload request.",
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(type = "string", example = "X-Datahub-Path must include a filename")
            ))
    @ApiResponse(responseCode = "409", description = "Upload failed, a file already exists at that path.",
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(type = "string", example = "A file already exists at the target path.")
            ))
    @RequestMapping(value = "", method = RequestMethod.PUT,
            produces = { "application/json", "application/xml" }
    )
    @Transactional
    public ResponseEntity<?> upload(HttpServletRequest request){
        if (isFilesDisabled()) {
            return new ResponseEntity<>(FILES_FEATURE_DISABLED, HttpStatus.FORBIDDEN);
        }
        try{
            // The file content is the raw request body; all metadata is in headers. Headers are
            // available before the body, so we fully validate and authorise the upload before
            // reading a single body byte. X-Datahub-Path is the full destination path (folder +
            // filename), each segment percent-encoded so non-ASCII / spaces survive the header.
            String rawPath = request.getHeader("X-Datahub-Path");
            if (rawPath == null || rawPath.isBlank()) {
                return new ResponseEntity<>("Missing required header: X-Datahub-Path", HttpStatus.BAD_REQUEST);
            }
            String fullPath = decodePath(rawPath);
            int lastSlash = fullPath.lastIndexOf('/');
            String filename = fullPath.substring(lastSlash + 1);
            String filePath = lastSlash <= 0 ? "/" : fullPath.substring(0, lastSlash);
            if (filename.isBlank()) {
                return new ResponseEntity<>("X-Datahub-Path must include a filename", HttpStatus.BAD_REQUEST);
            }
            if (!fileSystemService.validateFolderPath(filePath)) {
                return new ResponseEntity<>("Invalid folder path", HttpStatus.BAD_REQUEST);
            }

            INode datahubFile = new INode();
            datahubFile.setNodeType(INode.INodeType.FILE);
            fileTransformer.setProperty(datahubFile, "name", filename);
            datahubFile.setPath(filePath.endsWith("/") ? filePath + filename : filePath + "/" + filename);

            // External id: a client-supplied value (percent-decoded like the other headers), or a
            // default derived from the filename when the header is omitted. Either way it is ALWAYS
            // run through the slug sanitizer - that is the server-side protection that guarantees
            // external ids are safe (lowercased, alphanumerics and underscores only, no path or
            // control characters). Never omit this check, even for client-supplied ids.
            String externalId = request.getHeader("X-Datahub-External-Id");
            if (externalId == null || externalId.isBlank()) {
                externalId = filename;
            } else {
                externalId = URLDecoder.decode(externalId, StandardCharsets.UTF_8);
            }
            externalId = TextValidator.toSnakeLowerCasedAllowStartWithDigits(externalId);
            fileTransformer.setProperty(datahubFile, "externalId", externalId);
            // Optional metadata headers, each percent-encoded by the client. The two source dates
            // are epoch millis (UTC). These mirror the fields the old multipart form set.
            applyHeaderProperty(request, datahubFile, "X-Datahub-Description", "description");
            applyHeaderProperty(request, datahubFile, "X-Datahub-Dataset-Id", "dataSet");
            applyHeaderProperty(request, datahubFile, "X-Datahub-Source", "source");
            applyHeaderProperty(request, datahubFile, "X-Datahub-Source-Date-Created", "sourceDateCreated");
            applyHeaderProperty(request, datahubFile, "X-Datahub-Source-Last-Updated", "sourceLastUpdated");
            applyHeaderProperty(request, datahubFile, "X-Datahub-Metadata", "metadata");
            applyHeaderProperty(request, datahubFile, "X-Datahub-Related-Resources", "relatedResources");
            // The file's MIME type comes from Content-Type; "auto-detect" defers to Tika after the
            // bytes are stored (also the default when the client sends octet-stream or nothing).
            String requestContentType = request.getContentType();
            String mimeType = (requestContentType == null || requestContentType.isBlank()
                    || requestContentType.startsWith("application/octet-stream"))
                    ? "auto-detect" : requestContentType;
            fileTransformer.setProperty(datahubFile, "mimeType", mimeType);

            final Path destinationPath = Paths.get(filesConfig.getRoot().toString(), datahubFile.getPath())
                    .toAbsolutePath()
                    .normalize();

            Set<ConstraintViolation<INode>> violations = validator.validate(datahubFile);
            if (!violations.isEmpty()) {
                String errorMessage = violations.stream()
                        .map(v -> v.getPropertyPath() + ": " + v.getMessage())
                        .collect(Collectors.joining("; "));
                return new ResponseEntity<>(errorMessage, HttpStatus.BAD_REQUEST);
            }

            // A file with no dataset is public — anyone with file access may upload it. Otherwise
            // the caller must have write permission to the target dataset.
            Long uploadDataSetId = datahubFile.getDataSet() != null ? datahubFile.getDataSet().getId() : null;
            if (uploadDataSetId != null && !dataSecurity.hasWritePermissionToDataSet(uploadDataSetId)) {
                TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
                return new ResponseEntity<>(
                        "No write permission for data set: " + uploadDataSetId,
                        HttpStatus.FORBIDDEN
                );
            }

            // Adding content under an existing folder requires write access to that folder's
            // dataset. Public/no-dataset folders (e.g. the root) stay open to everyone; newly
            // created subfolders inherit the file's dataset, already authorised above.
            INode targetFolder = deepestExistingFolder(filePath);
            if (targetFolder != null && targetFolder.getDataSet() != null) {
                Long parentDataSetId = targetFolder.getDataSet().getId();
                if (!dataSecurity.hasWritePermissionToDataSet(parentDataSetId)) {
                    TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
                    return new ResponseEntity<>(
                            "No write permission for parent folder data set: " + parentDataSetId,
                            HttpStatus.FORBIDDEN
                    );
                }
            }

            // Reserve the path in the DB first. The unique partial index on
            // (path_hash WHERE is_deleted = false) plus the existing unique constraint on
            // external_id_hash make this the authoritative "path is taken" check across all
            // stateless API instances — no filesystem TOCTOU, no NFS caching surprises.
            try {
                fileTransformer.setDirectoryOrCreateIfMissing(datahubFile, filePath);
                iNodeRepository.save(datahubFile);
                iNodeRepository.flush();
            } catch (DataIntegrityViolationException e) {
                log.debug("Path or externalId already taken: {}", datahubFile.getPath());
                TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
                return new ResponseEntity<>(
                        "File with submitted path or externalId already exists.",
                        HttpStatus.CONFLICT
                );
            }

            // Stage the upload in the single per-tenant temp dir (TextValidator.RESERVED_TMP_DIR),
            // then atomically rename into place. CREATE_NEW on the temp path guards against a
            // concurrent writer; the rename is atomic because the tenant root is one filesystem.
            final Path tmpPath = filesConfig.getRoot()
                    .resolve(TextValidator.RESERVED_TMP_DIR)
                    .resolve(UUID.randomUUID() + ".part");
            try {
                handleUploadStream(tmpPath, request.getInputStream(), datahubFile);
                fileSystemService.createParentDirectories(destinationPath);
                Files.move(tmpPath, destinationPath, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException e) {
                log.error("Upload failed for {}: {}", destinationPath, e.getMessage(), e);
                try {
                    Files.deleteIfExists(tmpPath);
                } catch (IOException cleanupError) {
                    log.warn("Failed to clean up temp file {}: {}", tmpPath, cleanupError.getMessage());
                }
                throw new RuntimeException("File upload failed!");
            }

            log.debug("datahubFile: " + datahubFile);

            // Return the transformed DTO (like every other file endpoint), not the raw INode entity.
            // The entity's wire shape (nodeType/dataSet/parent nesting, base64 checksum, numeric id)
            // does not match the IndexNode contract the SDK and console expect. transformToIndexNode
            // runs inside this @Transactional method, before OSIV-off closes the session on its lazy
            // collections.
            DataWrapper<IndexNode> data = new DataWrapper<>();
            data.setItems(fileTransformer.transformToIndexNode(List.of(datahubFile)));
            return new ResponseEntity<>(data, HttpStatus.OK);
        } catch (IllegalArgumentException e){
            // Malformed header: bad percent-encoding, a non-numeric dataset id, or invalid
            // metadata / relatedResources JSON.
            TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
            return new ResponseEntity<>(e.getMessage(), HttpStatus.BAD_REQUEST);
        } catch (Exception e){
            log.error(e.getMessage(), e);
            // Mark transaction for rollback
            TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
            return new ResponseEntity<>(e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    /**
     * Streams the uploaded file body to {@code destinationPath} while computing its checksum in a
     * single pass over the body.
     *
     * <p><strong>Dev vs prod performance.</strong> The checksum is SHA-256, which the JVM
     * hardware-accelerates through a C2 compiler intrinsic (multi-GB/s). That intrinsic exists only
     * under C2, so any launcher that forces C1-only compilation drops SHA-256 to the ~200 MB/s
     * pure-Java path and makes uploads several times slower. The common culprits both inject
     * {@code -XX:TieredStopAtLevel=1}: IntelliJ's "Enable launch optimizations" and Spring Boot's
     * Gradle {@code bootRun} (its {@code optimizedLaunch} defaults to {@code true}). This is a
     * development-runtime artifact only; a plain {@code java -jar} (full tiered compilation) hashes
     * at full speed. If uploads look slow in dev, check {@code jcmd <pid> VM.flags} for
     * {@code TieredStopAtLevel=1}.
     */
    protected void handleUploadStream(Path destinationPath, InputStream in, INode datahubFile)
            throws IOException {
        fileSystemService.createParentDirectories(destinationPath);

        FileChecksum checksum = checksumFactory.create();
        byte[] buffer = new byte[uploadProperties.getBufferSize()];

        // Aggregate the servlet's small (~8 KB) socket reads into large blocks before writing and
        // hashing, so the file is written in few large syscalls and the hash runs on big chunks.
        try (OutputStream out = Files.newOutputStream(
                destinationPath,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE)) {

            long totalBytesRead = 0;
            int filled;
            while ((filled = fill(in, buffer)) > 0) {
                out.write(buffer, 0, filled);
                checksum.update(buffer, 0, filled);
                totalBytesRead += filled;
            }

            datahubFile.setSize(totalBytesRead);
            datahubFile.setChecksum(checksum.digest());

            if (datahubFile.getMimeType().equals("auto-detect")) {
                fileTransformer.setMimeType(datahubFile, destinationPath);
            }
        }
    }

    /** Reads into {@code buf} until it is full or EOF; returns bytes read, or -1 at immediate EOF. */
    private static int fill(InputStream in, byte[] buf) throws IOException {
        int n = 0;
        while (n < buf.length) {
            int r = in.read(buf, n, buf.length - n);
            if (r == -1) {
                return n == 0 ? -1 : n;
            }
            n += r;
        }
        return n;
    }

    /** If {@code headerName} is present, percent-decodes its value and sets {@code property} on the node. */
    private void applyHeaderProperty(HttpServletRequest request, INode node, String headerName, String property) {
        String value = request.getHeader(headerName);
        if (value != null && !value.isBlank()) {
            fileTransformer.setProperty(node, property, URLDecoder.decode(value, StandardCharsets.UTF_8));
        }
    }

    /**
     * Percent-decodes each slash-separated segment of an upload path. Clients encode each segment
     * with {@code encodeURIComponent} (so the path separators survive), so we decode segment by
     * segment to match.
     */
    private static String decodePath(String encodedPath) {
        String[] segments = encodedPath.split("/", -1);
        StringBuilder sb = new StringBuilder(encodedPath.length());
        for (int i = 0; i < segments.length; i++) {
            if (i > 0) {
                sb.append('/');
            }
            sb.append(URLDecoder.decode(segments[i], StandardCharsets.UTF_8));
        }
        return sb.toString();
    }

    @Tag(name = "Files")
    @Operation(summary = "List contents of a folder",
            description = "Lists the files and folders directly under the given folder path. The " +
                    "path is taken from the URL after /files/list (e.g. GET /files/list/docs/2024); " +
                    "an empty path lists the root. Results are limited to the caller's readable " +
                    "datasets; files and folders with no dataset are public."
    )
    @ApiResponse(responseCode = "200", description = "Folders and files found in the submitted folder.",
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = FileDataWrapper.class)
            ))
    @RequestMapping(
            value = { "/list", "/list/**"},
            method = RequestMethod.GET,
            produces = { "application/json", "application/xml" })
    public ResponseEntity<?> listDirectory(HttpServletRequest req){
        if (isFilesDisabled()) {
            return new ResponseEntity<>(FILES_FEATURE_DISABLED, HttpStatus.FORBIDDEN);
        }

        String foundPath = httpHelper.getRequestPath(req, "/files/list");

        DataWrapper<IndexNode> data = new DataWrapper<>();
        // Narrow the listing to the caller's readable datasets in SQL. Files/folders with no
        // dataset are public (visible to everyone); dataset-bearing ones show only if readable.
        boolean readAll = dataSecurity.hasReadAccessToEverything();
        Set<Long> allowed = readAll ? null : dataSecurity.readableDataSetIds();
        try{
            if(fileSystemService.validateFolderPath(foundPath)){
                List<INode> nodes;
                if(foundPath.isEmpty() || foundPath.equals("/")){
                    nodes = readAll
                            ? iNodeRepository.findAllByParentAndIsDeletedEquals(null, false, INode.class)
                            : iNodeRepository.findReadableInRoot(false, allowed, INode.class);
                } else {
                    var pathHash = IdGenerator.xxHash(foundPath);
                    nodes = readAll
                            ? iNodeRepository.findAllByParentPathHashAndIsDeletedEquals(pathHash, false, INode.class)
                            : iNodeRepository.findReadableByParentPathHash(pathHash, false, allowed, INode.class);
                }
                data.setItems(fileTransformer.transformToIndexNode(nodes));
                return new ResponseEntity<>(data, HttpStatus.OK);
            }
            return new ResponseEntity<>("", HttpStatus.NOT_FOUND);
        } catch (Exception e){
            log.error(e.getMessage(), e);
            return new ResponseEntity<>("Internal programming error.", HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @Tag(name = "Files")
    @Operation(summary = "Get a file or folder",
            description = "Return a single file or folder by numeric id or by externalId (supply one). "
                    + "Visible only if the caller can read its dataset; dataset-less nodes are public.")
    @ApiResponse(responseCode = "200", description = "The file or folder.",
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = FileDataWrapper.class)
            ))
    @ApiResponse(responseCode = "400", description = "Neither id nor externalId supplied.",
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(type = "string", example = "A file id or externalId is required.")
            ))
    @ApiResponse(responseCode = "404", description = "Not found or not readable.",
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(type = "string", example = "File or folder not found.")
            ))
    @RequestMapping(value = "", method = RequestMethod.GET, produces = { "application/json", "application/xml" })
    // Read-only transaction so the transformer's lazy metadata/relatedResources/dataSet loads succeed
    // (the by-id/externalId lookups don't @EntityGraph those the way the directory listing does).
    @Transactional(readOnly = true)
    public ResponseEntity<?> getByIdOrExternalId(
            @Parameter(description = "Numeric id of the file or folder. Supply this or externalId.", example = "5677892") @RequestParam(value = "id", required = false) Long id,
            @Parameter(description = "External id of the file or folder. Supply this or id.", example = "reports/2026/q1.pdf") @RequestParam(value = "externalId", required = false) String externalId) {
        if (isFilesDisabled()) {
            return new ResponseEntity<>(FILES_FEATURE_DISABLED, HttpStatus.FORBIDDEN);
        }
        boolean hasExternalId = externalId != null && !externalId.isBlank();
        if (!hasExternalId && id == null) {
            return new ResponseEntity<>("A file id or externalId is required.", HttpStatus.BAD_REQUEST);
        }
        Optional<INode> maybeNode = hasExternalId
                ? iNodeRepository.findByExternalIdHashAndIsDeletedIs(
                        LongHashFunction.xx3().hashChars(externalId), false, INode.class)
                : iNodeRepository.findByIdAndIsDeletedEquals(id, false, INode.class);
        if (maybeNode.isEmpty()) {
            return new ResponseEntity<>("File or folder not found.", HttpStatus.NOT_FOUND);
        }
        INode node = maybeNode.get();
        // Dataset-less nodes are public; otherwise the caller must be able to read the node's dataset.
        // Return 404 (not 403) so a hidden file's existence isn't leaked.
        if (!dataSecurity.hasReadAccessToEverything() && node.getDataSet() != null) {
            Set<Long> allowed = dataSecurity.readableDataSetIds();
            if (allowed == null || !allowed.contains(node.getDataSet().getId())) {
                return new ResponseEntity<>("File or folder not found.", HttpStatus.NOT_FOUND);
            }
        }
        DataWrapper<IndexNode> data = new DataWrapper<>();
        data.setItems(fileTransformer.transformToIndexNode(List.of(node)));
        return new ResponseEntity<>(data, HttpStatus.OK);
    }

    @Tag(name = "Files")
    @Operation(summary = "Search files and folders",
            description = """
                    Case-insensitive full-text search over file and folder names (and descriptions)
                    across the whole tree. Results are narrowed to the caller's readable datasets.

                    Unlike the resource, data set, timeseries and event searches this one is a `GET`
                    taking the phrase as `q`, and it has no structured `filter`: files are indexed
                    by the tree they live in rather than by the node criteria those four share. Use
                    `GET /files/list` to walk a folder.

                    `limit` caps the result size (default 100, max 1000), matching the cap the other
                    searches apply.""")
    @ApiResponse(responseCode = "200", description = "Matching files and folders.",
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = FileDataWrapper.class)
            ))
    @RequestMapping(value = "/search", method = RequestMethod.GET, produces = { "application/json", "application/xml" })
    // Read-only transaction so the transformer's lazy metadata/relatedResources load (the native FTS
    // query can't use an @EntityGraph the way the directory listing does).
    @Transactional(readOnly = true)
    public ResponseEntity<?> search(
            @Parameter(description = "Text to match against file and folder names.", example = "invoice")
            @RequestParam("q") String q,
            @Parameter(description = "Cap on results returned. Defaults to 100, max 1000.", example = "50")
            @RequestParam(value = "limit", required = false) Integer limit){
        if (isFilesDisabled()) {
            return new ResponseEntity<>(FILES_FEATURE_DISABLED, HttpStatus.FORBIDDEN);
        }
        DataWrapper<IndexNode> data = new DataWrapper<>();
        if (q == null || q.isBlank()) {
            return new ResponseEntity<>(data, HttpStatus.OK);
        }
        // Same clamp the four POST searches apply through their form models: a limit <= 0 becomes
        // LIMIT 0 in SQL and returns nothing, which reads as "no matches" rather than as a bad
        // request. The cap was previously fixed at 100 with no way for a caller to say otherwise.
        int cap = (limit == null || limit <= 0) ? SEARCH_LIMIT : Math.min(limit, MAX_SEARCH_LIMIT);
        try {
            List<INode> nodes;
            if (dataSecurity.hasReadAccessToEverything()) {
                nodes = iNodeRepository.searchByName(q.trim(), false, cap);
            } else {
                Set<Long> allowed = dataSecurity.readableDataSetIds();
                // Empty IN (...) is invalid SQL in a native query; a non-existent id keeps it valid and
                // matches nothing, so only public (no-dataset) inodes come back.
                Collection<Long> ids = allowed.isEmpty() ? List.of(-1L) : allowed;
                nodes = iNodeRepository.searchReadableByName(q.trim(), false, ids, cap);
            }
            data.setItems(fileTransformer.transformToIndexNode(nodes));
            return new ResponseEntity<>(data, HttpStatus.OK);
        } catch (Exception e) {
            log.error(e.getMessage(), e);
            return new ResponseEntity<>("Internal programming error.", HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @Tag(name = "Files")
    @Operation(summary = "Download file",
            description = "Download file"
    )
    @ApiResponse(responseCode = "200", description = "The file content as a binary stream.",
            content = @Content(
                    mediaType = MediaType.APPLICATION_OCTET_STREAM_VALUE,
                    schema = @Schema(type = "string", format = "binary")
            ))
    @RequestMapping(
            value = { "/download/{id}"},
            method = RequestMethod.GET,
            produces = { "application/octet-stream" })
    public ResponseEntity<?> download(HttpServletResponse res, @Parameter(description = "Numeric id of the file to download.", example = "5677892") @PathVariable Optional<String> id){
        if (isFilesDisabled()) {
            return new ResponseEntity<>(FILES_FEATURE_DISABLED, HttpStatus.FORBIDDEN);
        }

        try{
            Optional<INodeDownload> inode = Optional.empty();
            if(id.isPresent()){
                inode = findIndexNode(id.get(), inode);
                if (inode.isPresent()) {
                    String filesystemPath = filesConfig.getRoot().toString();
                    return doFileDownload(res, filesystemPath, inode.get());
                } else {
                    return new ResponseEntity<>("", HttpStatus.NOT_FOUND);
                }
            }
        } catch (Exception e){
            log.error(e.getMessage(), e);
        }
        return new ResponseEntity<>("", HttpStatus.INTERNAL_SERVER_ERROR);
    }

    private static ResponseEntity<?> doFileDownload(
            HttpServletResponse res,
            String filesystemPath,
            @NotNull INodeDownload inode
    ) {
        final Path sourcePath = Paths.get(filesystemPath, inode.getPath())
                .toAbsolutePath()
                .normalize();

        // Only the input file is ours to close. The servlet output stream is owned by the
        // container; closing it here throws AsyncRequestNotUsableException once the client has
        // aborted the download, so we leave it for the container to close.
        try (FileChannel inputChannel = FileChannel.open(sourcePath, StandardOpenOption.READ)) {
            res.setHeader("Content-Disposition",
                    "attachment; filename=\"" + inode.getName() + "\"");
            res.setContentLengthLong(inode.getSize());

            String mimeType = inode.getMimeType();
            if (mimeType == null || mimeType.isBlank() ||
                    "null".equalsIgnoreCase(mimeType) || !mimeType.contains("/")) {
                mimeType = "application/octet-stream";
            }
            res.setContentType(mimeType);

            WritableByteChannel outputChannel = Channels.newChannel(res.getOutputStream());
            inputChannel.transferTo(0, inputChannel.size(), outputChannel);
            return new ResponseEntity<>(HttpStatus.OK);
        } catch (IOException e) {
            if (isClientAbort(e)) {
                // The client cancelled or disconnected mid-download. Normal, not a server error;
                // the response is already committed, so there is nothing left to send.
                log.debug("Client aborted download of {}: {}", sourcePath, e.getMessage());
                return null;
            }
            log.error("Error downloading file: {}", sourcePath, e);
            return new ResponseEntity<>(
                    "Error downloading file",
                    HttpStatus.INTERNAL_SERVER_ERROR
            );
        }
    }

    /**
     * Whether {@code e} (or any of its causes) signals that the client aborted the transfer rather
     * than a real server-side failure: a Tomcat ClientAbortException, Spring's
     * AsyncRequestNotUsableException, or a broken-pipe / connection-reset socket error. Matched by
     * class name and message so we don't depend on container-internal types.
     */
    private static boolean isClientAbort(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            String className = t.getClass().getName();
            if (className.endsWith("ClientAbortException")
                    || className.endsWith("AsyncRequestNotUsableException")) {
                return true;
            }
            String message = t.getMessage();
            if (message != null) {
                String m = message.toLowerCase(Locale.ROOT);
                if (m.contains("broken pipe") || m.contains("connection reset")
                        || m.contains("response not usable")) {
                    return true;
                }
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return false;
    }

    @Tag(name = "Files")
    @Operation(summary = "Delete files or folders",
            description = "Delete file or folder by id or external id."
    )
    @ApiResponse(responseCode = "204", description = "File or folder was deleted. No response body.",
            content = @Content)
    @ApiResponse(responseCode = "409", description = "The folder is not empty.",
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(type = "string", example = "Folder is not empty.")
            ))
    @RequestMapping(
            value = { "/delete"},
            method = {RequestMethod.POST},
            consumes = {MediaType.APPLICATION_JSON_VALUE},
            produces = { "application/json", "application/xml" }
    )
    public ResponseEntity<?> delete(
            HttpServletRequest req,
            @RequestBody
            @Schema(implementation = IdCollectionDataWrapper.class)
            DataWrapper<IdCollection> data
    ){
        if (isFilesDisabled()) {
            return new ResponseEntity<>(FILES_FEATURE_DISABLED, HttpStatus.FORBIDDEN);
        }
        // Manually parse the JSON here, something weird is going on with Spring Boot
        try {
            // Read the entire input stream into a byte array
            byte[] rawRequestBodyBytes = req.getInputStream().readAllBytes();
            // Convert byte array to String using UTF-8 encoding
            String rawRequestBody = new String(rawRequestBodyBytes, StandardCharsets.UTF_8);

            // Manually deserialize using ObjectMapper for demonstration
            try {
                // Construct JavaType for DataWrapper<IdCollection>
                JavaType type = jsonMapper.getTypeFactory().constructParametricType(DataWrapper.class, IdCollection.class);
                data = jsonMapper.readValue(rawRequestBody, type);
            } catch (JsonIOException e) {
                log.error("Error during manual JSON deserialization: {}", e.getMessage(), e);
            }

        } catch (IOException e) {
            log.error("Error reading raw request body: {}", e.getMessage(), e);
        }

        Set<Long> idList = data.getItems().stream().map(IdCollection::getId).filter(Objects::nonNull).collect(Collectors.toSet());
        Set<String> externalIdHashes = data.getItems().stream()
                .map(IdCollection::getExternalId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        // Enforce write permission on the datasets of the files/folders being deleted (skipped for
        // write-all). Deleting a folder cascades to its whole subtree, so we check the datasets of
        // every descendant too — a public (no-dataset) folder containing dataset-bearing children
        // can't be deleted by someone who can't write those children's datasets. Public
        // (no-dataset) nodes are deletable by anyone.
        if (!dataSecurity.hasWriteAccessToEverything()) {
            Set<Long> extHashes = externalIdHashes.stream()
                    .map(it -> LongHashFunction.xx3().hashChars(it))
                    .collect(Collectors.toSet());
            Set<Long> rootIds = iNodeRepository.findAllByIdOrExternalIdHashAndNotDeleted(idList, extHashes)
                    .stream().map(INode::getId).collect(Collectors.toSet());
            if (!rootIds.isEmpty()) {
                for (Long dataSetId : iNodeRepository.findSubtreeDataSetIds(rootIds)) {
                    dataSecurity.assertCanWriteDataSet(dataSetId);
                }
            }
        }

        try{
            fileSystemService.delete(idList, externalIdHashes);
        } catch (IOException e){
            log.error(e.getMessage(), e);
            return new ResponseEntity<>(
                    "Internal delete operation error!",
                    HttpStatus.INTERNAL_SERVER_ERROR
            );
        }
        return new ResponseEntity<>(HttpStatus.NO_CONTENT);
    }

    @Tag(name = "Files")
    @Operation(summary = "List deleted files",
            description = "The soft-deleted files in the tenant trash that the caller can read. Their "
                    + "name and path are the pre-deletion values; the deletion time is encoded in the "
                    + "externalId (DELETED_..._<epochMillis>). Restore them via POST /files/restore.")
    @ApiResponse(responseCode = "200", description = "Deleted files in the trash.",
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = FileDataWrapper.class)
            ))
    @RequestMapping(value = "/trash", method = RequestMethod.GET, produces = { "application/json", "application/xml" })
    @Transactional(readOnly = true)
    public ResponseEntity<?> listTrash() {
        if (isFilesDisabled()) {
            return new ResponseEntity<>(FILES_FEATURE_DISABLED, HttpStatus.FORBIDDEN);
        }
        List<INode> deleted;
        if (dataSecurity.hasReadAccessToEverything()) {
            deleted = iNodeRepository.findAllDeletedByNodeType(INode.INodeType.FILE);
        } else {
            Set<Long> allowed = dataSecurity.readableDataSetIds();
            deleted = iNodeRepository.findReadableDeletedByNodeType(
                    INode.INodeType.FILE, allowed == null ? Set.of() : allowed);
        }
        DataWrapper<IndexNode> data = new DataWrapper<>();
        data.setItems(fileTransformer.transformToIndexNode(deleted));
        return new ResponseEntity<>(data, HttpStatus.OK);
    }

    @Tag(name = "Files")
    @Operation(summary = "Restore deleted files",
            description = "Move soft-deleted files out of the trash back to their original location and "
                    + "clear the deleted flag. Identify each by id or (trashed) externalId. Files only. "
                    + "Never overwrites: if a file's original name/path or externalId is already taken, or "
                    + "its original folder is gone, the request is refused (409) and nothing is restored.")
    @ApiResponse(responseCode = "200", description = "The restored files.",
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = FileDataWrapper.class)
            ))
    @ApiResponse(responseCode = "403", description = "No write permission on a file's dataset.",
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(type = "string", example = "No write permission on this dataset.")
            ))
    @ApiResponse(responseCode = "404", description = "None of the given ids/externalIds match a deleted file.",
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(type = "string", example = "File or folder not found.")
            ))
    @ApiResponse(responseCode = "409", description = "Original name/path or externalId already taken, or the original folder is gone.",
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(type = "string", example = "A file already exists at the original path.")
            ))
    @RequestMapping(value = "/restore", method = RequestMethod.POST,
            consumes = { MediaType.APPLICATION_JSON_VALUE },
            produces = { "application/json" })
    @Transactional
    public ResponseEntity<?> restore(
            HttpServletRequest req,
            @RequestBody @Schema(implementation = IdCollectionDataWrapper.class) DataWrapper<IdCollection> data
    ) {
        if (isFilesDisabled()) {
            return new ResponseEntity<>(FILES_FEATURE_DISABLED, HttpStatus.FORBIDDEN);
        }
        // Same manual parse as delete (Spring Boot binding quirk on this body shape).
        try {
            String raw = new String(req.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            JavaType type = jsonMapper.getTypeFactory().constructParametricType(DataWrapper.class, IdCollection.class);
            data = jsonMapper.readValue(raw, type);
        } catch (Exception e) {
            log.error("Error reading restore request body: {}", e.getMessage(), e);
            return new ResponseEntity<>("Invalid request body.", HttpStatus.BAD_REQUEST);
        }

        Set<Long> idList = data.getItems().stream().map(IdCollection::getId).filter(Objects::nonNull).collect(Collectors.toSet());
        // Hash the RAW external id (getExternalIdHash), NOT getExternalId() — the latter
        // snake-lowercases it, but a trashed id is DELETED_<checksum>_<id>_<epoch> (uppercase
        // DELETED), so sanitizing would change the hash and never match the stored value.
        Set<Long> extHashes = data.getItems().stream().map(IdCollection::getExternalIdHash).filter(Objects::nonNull)
                .collect(Collectors.toSet());

        List<INode> nodes = iNodeRepository.findAllByIdOrExternalIdHashAndDeleted(idList, extHashes);
        if (nodes.isEmpty()) {
            return new ResponseEntity<>("No matching deleted files.", HttpStatus.NOT_FOUND);
        }
        // Write permission on each node's dataset (public/no-dataset nodes restorable by anyone).
        if (!dataSecurity.hasWriteAccessToEverything()) {
            for (INode node : nodes) {
                if (node.getDataSet() != null) {
                    dataSecurity.assertCanWriteDataSet(node.getDataSet().getId());
                }
            }
        }
        try {
            List<INode> restored = fileSystemService.restore(nodes);
            DataWrapper<IndexNode> resp = new DataWrapper<>();
            resp.setItems(fileTransformer.transformToIndexNode(restored));
            return new ResponseEntity<>(resp, HttpStatus.OK);
        } catch (FileAlreadyExistsException e) {
            TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
            return new ResponseEntity<>("A file already exists at the original path.", HttpStatus.CONFLICT);
        } catch (IllegalStateException e) {
            TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
            return new ResponseEntity<>(e.getMessage(), HttpStatus.CONFLICT);
        } catch (IOException e) {
            log.error("File restore failed: {}", e.getMessage(), e);
            TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
            return new ResponseEntity<>("Internal restore error.", HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @Tag(name = "Files")
    @Operation(summary = "Update a file or folder",
            description = "Rename, move, reassign the dataset, or edit the description, source, "
                    + "metadata or related resources of a file or folder. Identify the node by "
                    + "externalId or id; every other field is optional and applied only when present. "
                    + "Moving creates the destination folder if it is missing. Assigning a dataset to "
                    + "a folder also fills it in on every descendant that currently has no dataset "
                    + "(already-governed subtrees are left untouched)."
    )
    @ApiResponse(responseCode = "200", description = "The updated file or folder.",
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = FileDataWrapper.class)
            ))
    @ApiResponse(responseCode = "400", description = "Invalid request (e.g. illegal name).",
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(type = "string", example = "Invalid folder path")
            ))
    @ApiResponse(responseCode = "403", description = "No write permission.",
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(type = "string", example = "No write permission on this dataset.")
            ))
    @ApiResponse(responseCode = "404", description = "File or folder not found.",
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(type = "string", example = "File or folder not found.")
            ))
    @ApiResponse(responseCode = "409", description = "A file or folder already exists at the target path.",
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(type = "string", example = "A file already exists at the target path.")
            ))
    @RequestMapping(value = "/update", method = RequestMethod.POST,
            consumes = { "application/json" },
            produces = { "application/json", "application/xml" }
    )
    @Transactional
    public ResponseEntity<?> update(HttpServletRequest httpRequest) {
        if (isFilesDisabled()) {
            return new ResponseEntity<>(FILES_FEATURE_DISABLED, HttpStatus.FORBIDDEN);
        }
        // Deserialize the body by hand: on /files/* POST endpoints the @RequestBody converter receives
        // an empty body (a long-standing Spring Boot quirk on this controller), so read the raw stream
        // directly — the same workaround delete() uses.
        FileUpdate request = null;
        try {
            String rawBody = new String(httpRequest.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!rawBody.isBlank()) {
                request = jsonMapper.readValue(rawBody, FileUpdate.class);
            }
        } catch (IOException | JacksonException e) {
            // Jackson 3's parse/bind failures (JacksonException) extend RuntimeException, not
            // IOException as in Jackson 2 — so a malformed body slipped past a bare IOException
            // catch and became a 500. Catch both; request stays null and yields the 400 below.
            log.error("Error reading /files/update request body: {}", e.getMessage(), e);
        }
        if (request == null || (request.getExternalId() == null && request.getId() == null)) {
            return new ResponseEntity<>("A file id or externalId is required.", HttpStatus.BAD_REQUEST);
        }

        Optional<INode> maybeNode = (request.getExternalId() != null)
                ? iNodeRepository.findByExternalIdHashAndIsDeletedIs(
                        LongHashFunction.xx3().hashChars(request.getExternalId()), false, INode.class)
                : iNodeRepository.findByIdAndIsDeletedEquals(request.getId(), false, INode.class);
        if (maybeNode.isEmpty()) {
            return new ResponseEntity<>("File or folder not found.", HttpStatus.NOT_FOUND);
        }
        INode node = maybeNode.get();

        boolean writeAll = dataSecurity.hasWriteAccessToEverything();
        // Modifying a node at all requires write access to its current dataset. (Permission checks
        // run before any mutation, so a DatasetAccessDeniedException — mapped to 403 — leaves the
        // transaction clean.)
        if (!writeAll && node.getDataSet() != null) {
            dataSecurity.assertCanWriteDataSet(node.getDataSet().getId());
        }

        try {
            // 1. Dataset (DB only). Done before the filesystem move so the move is the last mutation.
            if (request.getDataSetId() != null) {
                if (!writeAll) {
                    dataSecurity.assertCanWriteDataSet(request.getDataSetId());
                }
                fileTransformer.setProperty(node, "dataSet", String.valueOf(request.getDataSetId()));
                fileSystemService.cascadeDataSetToUnsetDescendants(node);
            }

            // 2. Description and other metadata (DB only). These are managed-entity mutations, so
            //    they flush on commit (the explicit save below covers the rename/move-less path).
            if (request.getDescription() != null) {
                node.setDescription(request.getDescription());
            }
            if (request.getSource() != null) {
                node.setSource(request.getSource());
            }
            if (request.getMetadata() != null) {
                node.setMetadata(request.getMetadata());
            }
            if (request.getRelatedResources() != null) {
                node.setRelatedResources(request.getRelatedResources());
            }

            // 3. Rename / move (filesystem + path cascade) — last, so it is the only step that can
            //    leave disk and DB diverged if it half-fails.
            if (request.getName() != null || request.getPath() != null) {
                INode destParent = node.getParent();
                String newParentPath = request.getPath();
                if (newParentPath != null) {
                    if (isRootPath(newParentPath)) {
                        destParent = null;
                    } else {
                        if (!writeAll) {
                            INode existing = deepestExistingFolder(newParentPath);
                            if (existing != null && existing.getDataSet() != null) {
                                dataSecurity.assertCanWriteDataSet(existing.getDataSet().getId());
                            }
                        }
                        // Auto-create the destination folder hierarchy, inheriting the node's dataset.
                        destParent = directoryService.createDirectoriesFromPath(newParentPath, node.getDataSet());
                    }
                }
                fileSystemService.renameOrMove(node, request.getName(), newParentPath, destParent);
            } else {
                iNodeRepository.save(node);
            }
        } catch (FileAlreadyExistsException e) {
            TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
            return new ResponseEntity<>("A file or folder already exists at the target path.", HttpStatus.CONFLICT);
        } catch (IllegalArgumentException e) {
            TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
            return new ResponseEntity<>(e.getMessage(), HttpStatus.BAD_REQUEST);
        } catch (IOException e) {
            log.error("File update failed for {}: {}", node.getPath(), e.getMessage(), e);
            TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
            return new ResponseEntity<>("Internal update error.", HttpStatus.INTERNAL_SERVER_ERROR);
        }

        // Return the transformed DTO (like the search endpoints), not the raw entity: OSIV is off, so
        // serializing the entity after the transaction closes trips a LazyInitializationException on
        // its lazy collections (e.g. labelEntities). transformToIndexNode runs inside the transaction.
        DataWrapper<IndexNode> data = new DataWrapper<>();
        data.setItems(fileTransformer.transformToIndexNode(List.of(node)));
        return new ResponseEntity<>(data, HttpStatus.OK);
    }

    /** True for the root path ("/" or empty, ignoring trailing slashes). */
    private static boolean isRootPath(String path) {
        if (path == null) {
            return true;
        }
        String p = path.trim();
        while (p.length() > 1 && p.endsWith("/")) {
            p = p.substring(0, p.length() - 1);
        }
        return p.isEmpty() || p.equals("/");
    }

    /**
     * The deepest folder that already exists on {@code folderPath}, walking from the full path up
     * towards the root. This is the folder new upload content is attached to (directly, or by
     * creating a leaf folder inside it). Returns {@code null} when no folder on the path exists yet
     * (the content lands in the public root area). Used to enforce write permission on the
     * containing folder's dataset.
     */
    private INode deepestExistingFolder(String folderPath) {
        if (folderPath == null || folderPath.isEmpty()) {
            return null;
        }
        String path = folderPath;
        while (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        while (!path.isEmpty()) {
            long pathHash = IdGenerator.xxHash(path);
            Optional<INode> folder = iNodeRepository.findByPathHashAndNodeType(
                    pathHash, INode.INodeType.FOLDER, INode.class);
            if (folder.isPresent()) {
                return folder.get();
            }
            int slash = path.lastIndexOf('/');
            if (slash < 0) {
                break;
            }
            path = path.substring(0, slash);
        }
        return null;
    }

    private Optional<INodeDownload> findIndexNode(@NotNull String id, Optional<INodeDownload> inode) {
        // Narrow the lookup to the caller's readable datasets in SQL. A public (no-dataset) file is
        // downloadable by everyone; a dataset-bearing file outside the caller's readable set isn't
        // found and the caller gets a 404.
        boolean readAll = dataSecurity.hasReadAccessToEverything();
        Set<Long> allowed = readAll ? null : dataSecurity.readableDataSetIds();
        try {
            long parsedId = Long.parseLong(id);
            if (parsedId > 0) {
                inode = readAll
                        ? iNodeRepository.findByIdAndIsDeletedEquals(parsedId, false, INodeDownload.class)
                        : iNodeRepository.findReadableById(parsedId, false, allowed, INodeDownload.class);
            }
        } catch (NumberFormatException e){
            // Try external Id
            String externalId = TextValidator.toSnakeLowerCasedAllowStartWithDigits(id);
            final long h = IdGenerator.xxHash(externalId);
            inode = readAll
                    ? iNodeRepository.findByExternalIdHashAndIsDeletedEquals(h, false, INodeDownload.class)
                    : iNodeRepository.findReadableByExternalIdHash(h, false, allowed, INodeDownload.class);
        }
        inode.ifPresent(idxNode -> log.debug("Found inode: " + idxNode.getExternalId()));
        return inode;
    }

}
