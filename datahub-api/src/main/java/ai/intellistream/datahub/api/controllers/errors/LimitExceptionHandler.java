// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.controllers.errors;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;

/**
 * Turns the two limit refusals into RFC 9457 responses, with the status carrying the difference
 * between them: a daily quota clears on its own, a lifetime ceiling does not.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
@Slf4j
public class LimitExceptionHandler {

    /**
     * <strong>429</strong> with {@code Retry-After}: the allowance returns at midnight UTC. The Java
     * SDK already treats 429 as retryable, so a client with buffering enabled spools the batch and
     * replays it once the window has rolled.
     */
    @ExceptionHandler(IngestQuotaExceededException.class)
    public ResponseEntity<ProblemDetail> handleQuotaExceeded(IngestQuotaExceededException ex) {
        log.info("Daily {} quota reached (limit {})", ex.getMetric(), ex.getLimit());

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS, ex.getMessage());
        problem.setTitle("Ingest quota exceeded");
        problem.setType(URI.create("https://intellistream.ai/errors/ingest-quota-exceeded"));
        problem.setProperty("metric", ex.getMetric());
        problem.setProperty("limit", ex.getLimit());
        problem.setProperty("retryAfter", ex.getRetryAfterSeconds());

        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(ex.getRetryAfterSeconds()))
                .body(problem);
    }

    /**
     * <strong>403</strong>, deliberately without {@code Retry-After}. Retrying will never succeed;
     * the ceiling moves when someone raises it, which is what the message says. Matches how the
     * files feature gate already answers a tenant it is switched off for.
     */
    @ExceptionHandler(TenantLimitReachedException.class)
    public ProblemDetail handleTenantLimitReached(TenantLimitReachedException ex) {
        log.info("Tenant limit reached for {} (limit {})", ex.getMetric(), ex.getLimit());

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, ex.getMessage());
        problem.setTitle("Tenant limit reached");
        problem.setType(URI.create("https://intellistream.ai/errors/tenant-limit-reached"));
        problem.setProperty("metric", ex.getMetric());
        problem.setProperty("limit", ex.getLimit());
        return problem;
    }
}
