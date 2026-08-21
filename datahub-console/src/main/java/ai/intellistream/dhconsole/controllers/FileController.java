// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.dhconsole.controllers;

import ai.intellistream.datahub.api.responses.DataWrapper;
import ai.intellistream.datahub.helpers.text.TextValidator;
import ai.intellistream.datahub.helpers.utils.HttpHelper;
import ai.intellistream.datahub.models.files.IndexNode;
import ai.intellistream.dhconsole.api.DatahubApi;
import ai.intellistream.dhconsole.security.AccessTokens;
import ai.intellistream.dhconsole.i18n.I18NHelper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.MessageSource;
import org.springframework.core.env.Environment;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Pattern;

@Controller
@RequestMapping(value = {"/files"})
@Slf4j
public class FileController {

    private final AccessTokens accessTokens;
    private final HttpHelper httpHelper;
    private final DatahubApi datahubApi;
    private final MessageSource messageSource;
    private final Environment environment;
    private final JsonMapper jsonMapper;

    @Value("${datahub.url:http://localhost:8081}")
    private String datahubUrl;

    public FileController(
            AccessTokens accessTokens,
            HttpHelper httpHelper,
            DatahubApi datahubApi,
            MessageSource messageSource,
            Environment environment,
            JsonMapper jsonMapper
    ) {
        this.accessTokens = accessTokens;
        this.httpHelper = httpHelper;
        this.datahubApi = datahubApi;
        this.messageSource = messageSource;
        this.environment = environment;
        this.jsonMapper = jsonMapper;
    }

    @RequestMapping(value = {"/list", "/list/", "/list/**"}, method = {RequestMethod.HEAD, RequestMethod.GET})
    public String list(HttpServletRequest req, Model model, Locale locale) {
        var features = datahubApi.getTenantFeatures();
        model.addAttribute("featureDisabled", !features.isFilesEnabled());
        if (!features.isFilesEnabled()) {
            return "files/index";
        }

        String foundPath = httpHelper.getRequestPath(req, "/files/list");
        // Expose the browsed folder so the upload form can pre-fill its path field.
        model.addAttribute("currentPath", foundPath);
        try{
            DataWrapper<IndexNode> nodes = datahubApi.listDirectory(foundPath);
            var sortedNodes = IndexNode.sort( nodes.getItems().stream().toList() );
            DataWrapper<IndexNode> sortedNodesWrapper = new DataWrapper<>();
            sortedNodesWrapper.setItems(new TreeSet<>(sortedNodes));
            log.debug(sortedNodesWrapper.getItems().toString());
            model.addAttribute("inodes", sortedNodesWrapper.getItems());
            // Full node data as JSON so the file-info dialog and the update form can read every
            // field (source, metadata, related resources, dates) without extra round-trips.
            model.addAttribute("inodesJson", jsonMapper.writeValueAsString(sortedNodesWrapper.getItems()));
            model.addAttribute("parentPath", "/files/list" + TextValidator.getParentPath(foundPath));
            model.addAttribute("i18nKeys", I18NHelper.getFileMessages());
        } catch (Exception e){
            log.error(e.getMessage());
            model.addAttribute("flashError",
                    messageSource.getMessage("internal.error.with.intellistream.api", null, locale));
        }

        return "files/index";
    }

    @RequestMapping(value = {"/download/{id}"}, method = {RequestMethod.HEAD, RequestMethod.GET})
    public void download(@PathVariable String id, HttpServletResponse response) {

        final String downloadUrl = datahubUrl + "/files/download/" + id;
        log.debug("Downloading file from: {}", downloadUrl);

        try(HttpClient client = HttpClient.newHttpClient()){
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(downloadUrl) )
                    .header("Authorization", accessTokens.bearer())
                    .GET()
                    .build();
            HttpResponse<InputStream> httpResponse = client.send(request, HttpResponse.BodyHandlers.ofInputStream());

            if (httpResponse.statusCode() == 200) {
                Map<String, List<String>> headers = httpResponse.headers().map();

                String filename = headers.getOrDefault("Content-Disposition", List.of()).stream()
                        .findFirst()
                        .map(FileController::extractUpstreamFilename)
                        .orElse("downloaded_file.txt");
                String contentType = headers.getOrDefault("Content-Type", List.of()).stream()
                        .findFirst()
                        .map(FileController::sanitizeContentType)
                        .orElse("application/octet-stream");

                // Use Spring's ContentDisposition builder so the filename is correctly
                // percent-encoded (RFC 6266 / RFC 5987). This neutralizes any CR/LF/quote smuggled
                // in by an upstream API response — the raw filename is never interpolated into
                // the header verbatim.
                ContentDisposition disposition = ContentDisposition.attachment()
                        .filename(filename, StandardCharsets.UTF_8)
                        .build();
                response.setContentType(contentType);
                response.setHeader(HttpHeaders.CONTENT_DISPOSITION, disposition.toString());

                // Only the upstream (API) input stream is ours to close. The servlet output stream
                // is owned by the container; closing it after a client abort throws
                // AsyncRequestNotUsableException.
                try (InputStream inputStream = httpResponse.body()) {
                    java.io.OutputStream outputStream = response.getOutputStream();
                    byte[] buffer = new byte[64 * 1024];
                    int bytesRead;
                    while ((bytesRead = inputStream.read(buffer)) != -1) {
                        outputStream.write(buffer, 0, bytesRead);
                    }
                    outputStream.flush();
                } catch (IOException e) {
                    if (isClientAbort(e)) {
                        // The browser cancelled / disconnected mid-download. Normal, not an error.
                        log.debug("Client aborted download of {}: {}", downloadUrl, e.getMessage());
                        return;
                    }
                    log.error("Error during file download: {}", e.getMessage(), e);
                    if (!response.isCommitted()) {
                        response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
                    }
                }
            } else {
                response.setStatus(httpResponse.statusCode());
            }
        }
        catch (InterruptedException e) {
            log.error("Error sending HTTP request: {}", e.getMessage(), e);
            Thread.currentThread().interrupt();
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
        }
        catch (IOException e) {
            if (isClientAbort(e)) {
                log.debug("Client aborted download of {}: {}", downloadUrl, e.getMessage());
                return;
            }
            log.error("Error sending HTTP request: {}", e.getMessage(), e);
            if (!response.isCommitted()) {
                response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            }
        }

    }

    /**
     * Whether {@code e} (or any of its causes) signals that the browser aborted the download rather
     * than a real failure: a Tomcat ClientAbortException, Spring's AsyncRequestNotUsableException,
     * or a broken-pipe / connection-reset socket error. Matched by class name and message so we
     * don't depend on container-internal types.
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
                String m = message.toLowerCase(java.util.Locale.ROOT);
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

    /** RFC 7230 defines a content-type token as a tightly restricted character set. */
    private static final Pattern CONTENT_TYPE_PATTERN =
            Pattern.compile("[A-Za-z0-9!#$&^_.+\\-]+/[A-Za-z0-9!#$&^_.+\\-]+" +
                    "(\\s*;\\s*[A-Za-z0-9!#$&^_.+\\-]+=(\"[^\"\\r\\n]*\"|[A-Za-z0-9!#$&^_.+\\-]+))*");

    /**
     * Parse the upstream Content-Disposition via Spring's RFC 6266 parser, falling back to a
     * default name when parsing fails or the header is malformed. The returned string is further
     * scrubbed of path separators and control characters so that even if the parser accepted a
     * hostile filename, we don't propagate it.
     */
    static String extractUpstreamFilename(String disposition) {
        String raw = null;
        try {
            raw = ContentDisposition.parse(disposition).getFilename();
        } catch (IllegalArgumentException e) {
            log.warn("Unparseable upstream Content-Disposition; using default filename");
        }
        if (raw == null || raw.isBlank()) return "downloaded_file.txt";

        // Strip control characters (including CR/LF/NUL), path separators, and leading dots.
        String sanitized = raw.replaceAll("[\\p{Cntrl}/\\\\]", "_").strip();
        while (sanitized.startsWith(".")) sanitized = sanitized.substring(1);
        if (sanitized.isEmpty()) return "downloaded_file.txt";
        if (sanitized.length() > 255) sanitized = sanitized.substring(0, 255);
        return sanitized;
    }

    /**
     * Only propagate the upstream Content-Type when it matches a conservative MIME grammar with no
     * embedded CR/LF. Anything else collapses to application/octet-stream so a compromised
     * upstream cannot smuggle extra response headers via a malformed media type.
     */
    static String sanitizeContentType(String contentType) {
        if (contentType == null) return "application/octet-stream";
        String trimmed = contentType.strip();
        if (!CONTENT_TYPE_PATTERN.matcher(trimmed).matches()) {
            return "application/octet-stream";
        }
        return trimmed;
    }
}
