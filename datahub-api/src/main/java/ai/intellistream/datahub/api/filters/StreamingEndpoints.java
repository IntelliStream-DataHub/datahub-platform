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
     * The binary datapoint insert ({@code POST /timeseries/data/binary}) and read
     * ({@code POST /timeseries/data/list/binary}). Not streaming endpoints: their request bodies are
     * capped and charged like any other. They are only kept out of the body-cache filter, because a
     * body of up to 64 MiB that the controller reads once has nothing to gain from a second copy, a
     * read's frames are written as they are fetched and must not pile up in a response buffer, and
     * a request log must never carry either.
     */
    public static boolean isBinaryDatapoints(HttpServletRequest request) {
        String uri = path(request);
        if (uri == null || !"POST".equalsIgnoreCase(request.getMethod())) {
            return false;
        }
        if (uri.endsWith("/")) {
            uri = uri.substring(0, uri.length() - 1);
        }
        return uri.equals("/timeseries/data/binary") || uri.equals("/timeseries/data/list/binary");
    }

    /**
     * The network read ({@code POST /resources/fetch-related}). Its response can be 100 MB or more and
     * is sent while it is read, so the body-cache filter must not copy it into memory to log it. Its
     * request is small, and is size-limited like any other.
     */
    public static boolean isNetworkRead(HttpServletRequest request) {
        String uri = path(request);
        return uri != null
                && "POST".equalsIgnoreCase(request.getMethod())
                && (uri.equals("/resources/fetch-related") || uri.equals("/resources/fetch-related/"));
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
