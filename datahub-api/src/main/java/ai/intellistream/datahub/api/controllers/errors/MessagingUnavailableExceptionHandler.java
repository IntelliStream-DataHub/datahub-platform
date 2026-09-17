// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.controllers.errors;

import lombok.extern.slf4j.Slf4j;
import org.apache.pulsar.client.api.PulsarClientException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * The broker refused or dropped a publish, as a 503.
 *
 * <p>Writes that go through Pulsar — datapoint and event ingest, timeseries mutations — declare
 * {@link PulsarClientException} because it is checked. Every controller used to catch it inside a
 * {@code catch (PulsarClientException | RuntimeException e)} that answered 500 with no body, which
 * told the caller nothing and, being a 500, invited no retry.
 *
 * <p>503 rather than 500: the request was well formed and the failure is transient, so retrying it
 * is the right thing to do — which is what a 5xx that is not 500 tells a client, and what the
 * Java SDK's retry policy keys on.
 */
@RestControllerAdvice
@Slf4j
public class MessagingUnavailableExceptionHandler {

    @ExceptionHandler(PulsarClientException.class)
    public ProblemDetail handle(PulsarClientException ex) {
        log.error("Publish failed: {}", ex.getMessage(), ex);
        return Problems.of(HttpStatus.SERVICE_UNAVAILABLE, Problems.MESSAGING_UNAVAILABLE,
                "Service Unavailable",
                "The request could not be published for processing. It was not accepted; retry it.");
    }
}
