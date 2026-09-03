// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.controllers.errors;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The problem body's detail is the sentence the limit exception composes from its own numbers,
 * reached through {@link LimitException#detail()} rather than the exception message, so nothing
 * that arrived from a request or a lower layer can end up in a response.
 */
class LimitExceptionHandlerTest {

    private final LimitExceptionHandler handler = new LimitExceptionHandler();

    @Test
    void quotaRefusalCarriesTheComposedDetailAndTheRetryAfter() {
        var refusal = new IngestQuotaExceededException("events", 100_000, 43_200);

        ResponseEntity<ProblemDetail> response = handler.handleQuotaExceeded(refusal);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("43200");
        ProblemDetail body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.getDetail()).isEqualTo(refusal.detail())
                .isEqualTo("Daily events ingest quota (100000) is spent; it resets at 00:00 UTC.");
        assertThat(body.getProperties()).containsEntry("metric", "events").containsEntry("limit", 100_000L);
    }

    @Test
    void ceilingRefusalCarriesTheComposedDetailAndNoRetryAfter() {
        var refusal = new TenantLimitReachedException("events", 25_000);

        ProblemDetail body = handler.handleTenantLimitReached(refusal);

        assertThat(body.getStatus()).isEqualTo(HttpStatus.FORBIDDEN.value());
        assertThat(body.getDetail()).isEqualTo(refusal.detail()).contains("25000 events");
        assertThat(body.getProperties()).containsEntry("metric", "events").containsEntry("limit", 25_000L);
    }
}
