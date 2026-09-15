// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.controllers.errors;

import ai.intellistream.datahub.api.filters.RequestIdFilter;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.webmvc.error.ErrorAttributes;
import org.springframework.boot.webmvc.error.ErrorController;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.ServletWebRequest;

import java.net.URI;

/** Renders every failure no advice answered (sendError from a filter, an uncaught exception) as a problem. */
@Hidden
@RestController
@Slf4j
public class ProblemErrorController implements ErrorController {

    private final ErrorAttributes errorAttributes;

    public ProblemErrorController(ErrorAttributes errorAttributes) {
        this.errorAttributes = errorAttributes;
    }

    @RequestMapping("${server.error.path:${error.path:/error}}")
    public ResponseEntity<ProblemDetail> error(HttpServletRequest request) {
        ProblemDetail problem = describe(request);
        if (request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI) instanceof String uri) {
            problem.setInstance(URI.create(uri));
        }
        return ResponseEntity.status(problem.getStatus())
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problem);
    }

    private ProblemDetail describe(HttpServletRequest request) {
        Throwable error = errorAttributes.getError(new ServletWebRequest(request));
        Object code = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
        // A direct GET /error carries neither, and there is nothing here to show.
        int status = code instanceof Integer c ? c : error == null ? 404 : 500;

        if (error instanceof ErrorResponse framework && status < 500) {
            // Spring's own 4xx wording names the header or parameter and never quotes a stack.
            return Problems.forStatus(status, framework.getBody().getDetail());
        }
        if (status >= 500 && error != null) {
            log.error("Unhandled {} on {} {}, requestId {}", error.getClass().getSimpleName(), request.getMethod(),
                    request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI), RequestIdFilter.current(request), error);
        }
        // The servlet error message is never forwarded: for an uncaught exception it is the exception's text.
        return Problems.forStatus(status, null);
    }
}
