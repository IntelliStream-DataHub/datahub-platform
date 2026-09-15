// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.controllers.errors;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;

/** Renders a refused binary datapoint request as an RFC 9457 problem, reason and frame included. */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
@Slf4j
public class DatapointBlockExceptionHandler {

    public static final String TYPE = "https://intellistream.ai/errors/datapoint-block-rejected";

    @ExceptionHandler(DatapointBlockRejectedException.class)
    public ResponseEntity<ProblemDetail> handle(DatapointBlockRejectedException ex) {
        log.info("Binary datapoint request rejected: {} ({})", ex.getReason(), ex.getMessage());

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(ex.getStatus(), ex.getMessage());
        problem.setTitle("Datapoint block rejected");
        problem.setType(URI.create(TYPE));
        problem.setProperty("reason", ex.getReason());
        if (ex.getFrameIndex() != null) {
            problem.setProperty("frameIndex", ex.getFrameIndex());
        }
        if (!ex.getTimeseriesIds().isEmpty()) {
            problem.setProperty("timeseriesIds", ex.getTimeseriesIds());
        }
        ResponseEntity.BodyBuilder response = ResponseEntity.status(ex.getStatus());
        if (ex.getRetryAfterSeconds() != null) {
            response.header(HttpHeaders.RETRY_AFTER, String.valueOf(ex.getRetryAfterSeconds()));
        }
        return response.body(problem);
    }
}
