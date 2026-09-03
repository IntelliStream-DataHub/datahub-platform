// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.filters;

import jakarta.servlet.http.HttpServletRequest;

/**
 * The endpoints whose bodies stream and must never be buffered or counted: the file download
 * ({@code GET /files/download/**}) and upload ({@code PUT /files}), and the graph export
 * ({@code GET /resources/export/{id}}) and import ({@code POST /resources/import}). Shared so the
 * body-cache filter and the body-size cap cannot disagree about which requests are exempt.
 */
public final class StreamingEndpoints {

    private StreamingEndpoints() {
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
        String method = request.getMethod();
        if (uri.startsWith("/files/download/")) {
            return true;
        }
        if (uri.startsWith("/resources/export/")) {
            return "GET".equalsIgnoreCase(method);
        }
        if (uri.equals("/resources/import") || uri.equals("/resources/import/")) {
            return "POST".equalsIgnoreCase(method);
        }
        return "PUT".equalsIgnoreCase(method) && (uri.equals("/files") || uri.equals("/files/"));
    }
}
