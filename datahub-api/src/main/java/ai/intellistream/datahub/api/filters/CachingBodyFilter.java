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

    // Exceptions propagate: swallowing one here left the status at 200 and the body empty.
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
        chain.doFilter(reqWrapper, resWrapper);
        resWrapper.copyBodyToResponse();
    }

}
