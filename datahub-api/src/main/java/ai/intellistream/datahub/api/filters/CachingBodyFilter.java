// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.filters;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.util.ContentCachingRequestWrapper;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.IOException;

public class CachingBodyFilter implements Filter {

    // https://stackoverflow.com/questions/39935190/contentcachingresponsewrapper-produces-empty-response

    /**
     * Nothing is caught here, deliberately. This filter exists to make bodies loggable, so a
     * failure below it is never its business to handle: swallowing one would leave the response
     * at Tomcat's default 200 with an empty body, reporting a request that was dropped on the
     * floor as a success. DispatcherServlet wraps everything a handler throws — {@code Error}s
     * included — into {@code ServletException: Handler dispatch failed}, so a catch here would
     * mask every unhandled failure on every endpoint, not just the odd I/O fault.
     */
    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest httpRequest = (HttpServletRequest) request;

        // Streaming endpoints must NOT be wrapped: a file download or graph export response can be
        // many GB and ContentCachingResponseWrapper buffers the whole body in memory and rejects a
        // Content-Length above 2 GB (Integer.MAX_VALUE); a file upload or graph import body is large
        // and is parsed straight off the raw stream. ReqLogService only logs bodies when these
        // wrappers are present, so passing the raw request/response through simply skips body
        // logging here.
        if (StreamingEndpoints.matches(httpRequest)) {
            chain.doFilter(request, response);
            return;
        }

        ContentCachingRequestWrapper reqWrapper = new ContentCachingRequestWrapper(httpRequest, 1024 * 1024 * 20);
        ContentCachingResponseWrapper resWrapper = new ContentCachingResponseWrapper((HttpServletResponse) response);
        try {
            chain.doFilter(reqWrapper, resWrapper);
        } finally {
            // In a finally, not after the call: whatever was buffered before a failure has to reach
            // the real response either way, or an error body written further down is discarded and
            // the caller gets an empty one. Copying an empty buffer is a no-op, so the container is
            // still free to write its own error page over an uncommitted response.
            resWrapper.copyBodyToResponse();
        }
    }

}
