// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.controllers.errors;

import ai.intellistream.datahub.api.datasecurity.DatasetAccessDeniedException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;

/**
 * Translates {@link AccessDeniedException}s thrown from controllers/services (e.g. the dataset-ACL
 * checks in {@code DataSecurity}) into an RFC 9457 {@code application/problem+json} 403 response.
 *
 * <p>The status stays 403 — the value here is a machine-readable body. For the dataset-specific
 * {@link DatasetAccessDeniedException} the response additionally carries the offending
 * {@code dataSetId} and the attempted {@code permission} so clients can branch without string
 * matching.
 *
 * <p>Note this only covers exceptions that reach Spring MVC's exception resolver (i.e. thrown inside
 * request handling). Authorization failures raised inside the Spring Security filter chain
 * (method security, missing token) are still handled there.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
@Slf4j
public class AccessDeniedExceptionHandler {

    @ExceptionHandler(AccessDeniedException.class)
    public ProblemDetail handleAccessDenied(AccessDeniedException ex) {
        log.debug("Access denied: {}", ex.getMessage());

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, ex.getMessage());
        problem.setTitle("Forbidden");

        if (ex instanceof DatasetAccessDeniedException denied) {
            problem.setType(URI.create("https://intellistream.ai/errors/dataset-forbidden"));
            problem.setProperty("dataSetId", denied.getDataSetId());
            problem.setProperty("permission", denied.getPermission());
        }
        return problem;
    }
}
