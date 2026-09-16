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
        String uri = path(request);
        if (uri == null) {
            return false;
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

    /**
     * The binary datapoint insert ({@code POST /timeseries/data/binary}). Not a streaming endpoint:
     * its body is capped and charged like any other. It is only kept out of the body-cache filter,
     * because a body of up to 64 MiB that the controller reads once has nothing to gain from a
     * second copy, and a request log must never carry it.
     */
    public static boolean isBinaryDatapointInsert(HttpServletRequest request) {
        String uri = path(request);
        return uri != null
                && "POST".equalsIgnoreCase(request.getMethod())
                && (uri.equals("/timeseries/data/binary") || uri.equals("/timeseries/data/binary/"));
    }

    private static String path(HttpServletRequest request) {
        String uri = request.getRequestURI();
        if (uri == null) {
            return null;
        }
        String contextPath = request.getContextPath();
        if (contextPath != null && !contextPath.isEmpty() && uri.startsWith(contextPath)) {
            uri = uri.substring(contextPath.length());
        }
        return uri;
    }
}
