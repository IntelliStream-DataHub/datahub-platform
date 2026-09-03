// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.filters;

import jakarta.servlet.http.HttpServletRequest;

/**
 * The file endpoints whose bodies stream and must never be buffered or counted: the download
 * ({@code GET /files/download/**}) and the upload ({@code PUT /files}). Shared so the body-cache
 * filter and the body-size cap cannot disagree about which requests are exempt.
 */
public final class StreamingFileEndpoints {

    private StreamingFileEndpoints() {
    }

    public static boolean matches(HttpServletRequest request) {
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
