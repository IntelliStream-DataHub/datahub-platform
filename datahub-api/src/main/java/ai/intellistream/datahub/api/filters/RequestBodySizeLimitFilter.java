// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.filters;

import ai.intellistream.datahub.api.config.LimitsProperties;
import ai.intellistream.datahub.api.controllers.errors.IngestQuotaExceededException;
import ai.intellistream.datahub.api.controllers.errors.LimitExceptionHandler;
import ai.intellistream.datahub.api.controllers.errors.ProblemResponses;
import ai.intellistream.datahub.api.controllers.errors.Problems;
import ai.intellistream.datahub.api.services.IngestQuotaService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Rejects a request body larger than the configured cap.
 *
 * <p>The api is not only reached through nginx — anything that can talk to the service port sends
 * whatever it likes — so the ceiling has to exist in the application. {@code Content-Length} is
 * checked before the body is read; a chunked body with no declared length is counted as it is
 * consumed, so neither shape can get past the cap.
 *
 * <p>413 rather than 429 is deliberate: the size of a request never becomes acceptable by waiting,
 * and the Java SDK retries 429/5xx while surfacing 4xx to the caller.
 */
@Slf4j
public class RequestBodySizeLimitFilter extends OncePerRequestFilter {

    private static final String DATAPOINT_INSERT_PATH = "/timeseries/data";
    private static final String DATAPOINT_BINARY_PATH = "/timeseries/data/binary";

    private final LimitsProperties limits;
    private final IngestQuotaService ingestQuota;

    public RequestBodySizeLimitFilter(LimitsProperties limits, IngestQuotaService ingestQuota) {
        this.limits = limits;
        this.ingestQuota = ingestQuota;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        // File uploads stream to disk and graph imports are committed segment by segment as they
        // arrive; the downloads and exports have no request body worth counting.
        if (StreamingEndpoints.matches(request)) {
            chain.doFilter(request, response);
            return;
        }

        long limit = limitFor(request);
        if (limit <= 0) {
            chain.doFilter(request, response);
            return;
        }

        long declared = request.getContentLengthLong();
        if (declared > limit) {
            reject(request, response, limit, declared);
            return;
        }

        // Bytes are charged here because this is where a body's size is known. It is the quota that
        // actually bounds storage growth: an entity count does not, since one legal entity may be a
        // few hundred KB. Over the daily allowance surfaces as the same 429 a controller would give.
        if (declared > 0 && isWrite(request.getMethod())) {
            try {
                ingestQuota.checkAndRecord(IngestQuotaService.QuotaMetric.BYTES, declared);
            } catch (IngestQuotaExceededException e) {
                refuseOverQuota(request, response, e);
                return;
            }
        }

        try {
            chain.doFilter(new CountingRequestWrapper(request, limit), response);
        } catch (RequestBodyTooLargeException e) {
            // A body with no usable Content-Length is only found to be too large while it is read.
            // Where the handler read the stream itself, nothing on the way out answers that, so it is
            // answered here with the same 413 the pre-check gives.
            reject(request, response, limit, -1);
        }
    }

    private static boolean isWrite(String method) {
        return "POST".equalsIgnoreCase(method)
                || "PUT".equalsIgnoreCase(method)
                || "PATCH".equalsIgnoreCase(method)
                || "DELETE".equalsIgnoreCase(method);
    }

    /** Same 429 the advice would produce, written here because a filter never reaches one. */
    private static void refuseOverQuota(HttpServletRequest request, HttpServletResponse response,
                                        IngestQuotaExceededException e) throws IOException {
        if (response.isCommitted()) {
            return;
        }
        response.reset();
        response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(e.getRetryAfterSeconds()));
        ProblemResponses.write(request, response, LimitExceptionHandler.quotaProblem(e));
    }

    /** Datapoint inserts get their own, larger ceiling; everything else shares the general one. */
    private long limitFor(HttpServletRequest request) {
        String uri = request.getRequestURI();
        if (uri == null) {
            return limits.getMaxBodyBytes();
        }
        String contextPath = request.getContextPath();
        if (contextPath != null && !contextPath.isEmpty() && uri.startsWith(contextPath)) {
            uri = uri.substring(contextPath.length());
        }
        if (uri.equals(DATAPOINT_BINARY_PATH) || uri.equals(DATAPOINT_BINARY_PATH + "/")) {
            return limits.getMaxBodyBytesDatapointsBinary();
        }
        if (uri.equals(DATAPOINT_INSERT_PATH) || uri.equals(DATAPOINT_INSERT_PATH + "/")) {
            return limits.getMaxBodyBytesDatapoints();
        }
        return limits.getMaxBodyBytes();
    }

    /**
     * Written here rather than raised as an exception: a filter sits outside the
     * {@code @RestControllerAdvice} chain, so nothing downstream would shape the body.
     */
    private static void reject(HttpServletRequest request, HttpServletResponse response, long limit, long actual)
            throws IOException {
        log.warn("Rejecting oversized request body on {}: {} bytes, limit {}",
                request.getRequestURI(), actual < 0 ? "unknown" : actual, limit);
        if (response.isCommitted()) {
            return;
        }
        response.reset();
        ProblemDetail problem = Problems.of(HttpStatus.PAYLOAD_TOO_LARGE, Problems.REQUEST_TOO_LARGE,
                "Request body too large", "The request body exceeds the %d byte limit for this endpoint.".formatted(limit));
        problem.setProperty("limitBytes", limit);
        ProblemResponses.write(request, response, problem);
    }

    /** Fails the read as soon as more than {@code limit} bytes have been consumed. */
    private static final class CountingRequestWrapper extends HttpServletRequestWrapper {

        private final long limit;

        private CountingRequestWrapper(HttpServletRequest request, long limit) {
            super(request);
            this.limit = limit;
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            return new CountingServletInputStream(super.getInputStream(), limit);
        }
    }

    private static final class CountingServletInputStream extends ServletInputStream {

        private final ServletInputStream delegate;
        private final long limit;
        private long count;

        private CountingServletInputStream(ServletInputStream delegate, long limit) {
            this.delegate = delegate;
            this.limit = limit;
        }

        @Override
        public int read() throws IOException {
            int b = delegate.read();
            if (b != -1) {
                add(1);
            }
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int read = delegate.read(b, off, len);
            if (read > 0) {
                add(read);
            }
            return read;
        }

        private void add(int read) throws IOException {
            count += read;
            if (count > limit) {
                throw new RequestBodyTooLargeException(limit);
            }
        }

        @Override
        public boolean isFinished() {
            return delegate.isFinished();
        }

        @Override
        public boolean isReady() {
            return delegate.isReady();
        }

        @Override
        public void setReadListener(ReadListener readListener) {
            delegate.setReadListener(readListener);
        }

        @Override
        public int available() throws IOException {
            return delegate.available();
        }

        @Override
        public void close() throws IOException {
            delegate.close();
        }
    }

    /**
     * Thrown mid-read for a body with no usable {@code Content-Length}. Where the body is bound with
     * {@code @RequestBody} it surfaces as an unreadable request body, which the api answers with a
     * 400. Where a handler reads the stream itself, it propagates back out to this filter, which
     * answers it with a 413 while the response is still uncommitted.
     */
    public static class RequestBodyTooLargeException extends IOException {
        public RequestBodyTooLargeException(long limit) {
            super("Request body exceeds the " + limit + " byte limit.");
        }
    }
}
