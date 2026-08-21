// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.filters;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.util.ContentCachingRequestWrapper;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.IOException;

@Slf4j
public class CachingBodyFilter implements Filter {

    // https://stackoverflow.com/questions/39935190/contentcachingresponsewrapper-produces-empty-response

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) {
        HttpServletRequest httpRequest = (HttpServletRequest) request;

        // Streaming file endpoints must NOT be wrapped: the download response can be many GB and
        // ContentCachingResponseWrapper buffers the whole body in memory and rejects a
        // Content-Length above 2 GB (Integer.MAX_VALUE); the upload request body is large and we
        // parse it straight off the raw stream. ReqLogService only logs bodies when these wrappers
        // are present, so passing the raw request/response through simply skips body logging here.
        if (isStreamingFileEndpoint(httpRequest)) {
            try {
                chain.doFilter(request, response);
            } catch (IOException | ServletException e) {
                log.error("Error in streaming file request", e);
            }
            return;
        }

        ContentCachingRequestWrapper reqWrapper = new ContentCachingRequestWrapper(httpRequest, 1024 * 1024 * 20);
        ContentCachingResponseWrapper resWrapper = new ContentCachingResponseWrapper((HttpServletResponse) response);
        try {
            chain.doFilter(reqWrapper, resWrapper);
            resWrapper.copyBodyToResponse();
        } catch (IOException | ServletException e) {
            log.error("Error extracting body", e);
        }
    }

    /**
     * True for the file streaming endpoints whose bodies must not be buffered in memory: the file
     * download ({@code GET /files/download/**}) and the file upload ({@code PUT /files}).
     */
    private static boolean isStreamingFileEndpoint(HttpServletRequest request) {
        String uri = request.getRequestURI();
        if (uri == null) {
            return false;
        }
        String contextPath = request.getContextPath();
        if (contextPath != null && !contextPath.isEmpty() && uri.startsWith(contextPath)) {
            uri = uri.substring(contextPath.length());
        }
        if (uri.startsWith("/files/download/")) {
            return true;
        }
        return "PUT".equalsIgnoreCase(request.getMethod()) && (uri.equals("/files") || uri.equals("/files/"));
    }
}
