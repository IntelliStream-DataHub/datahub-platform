// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.controllers.errors;

import ai.intellistream.datahub.api.filters.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.http.server.ServletServerHttpResponse;

import java.io.IOException;
import java.net.URI;

/**
 * Writes a problem from a servlet filter, where no advice runs. Not via sendError: the error dispatch
 * re-runs bearer authentication, which answers a bad token a second time with no body.
 */
public final class ProblemResponses {

    // The default converter registers the mixin that puts extension members at the top level.
    private static final JacksonJsonHttpMessageConverter CONVERTER = new JacksonJsonHttpMessageConverter();

    private ProblemResponses() {
    }

    public static void write(HttpServletRequest request, HttpServletResponse response, ProblemDetail problem)
            throws IOException {
        if (response.isCommitted()) {
            return;
        }
        if (problem.getInstance() == null && request.getRequestURI() != null) {
            problem.setInstance(URI.create(request.getRequestURI()));
        }
        Problems.decorate(problem, RequestIdFilter.current(request));
        // resetBuffer, not reset: WWW-Authenticate and Retry-After are already set and must survive.
        response.resetBuffer();
        ServletServerHttpResponse out = new ServletServerHttpResponse(response);
        out.setStatusCode(HttpStatusCode.valueOf(problem.getStatus()));
        CONVERTER.write(problem, MediaType.APPLICATION_PROBLEM_JSON, out);
        out.flush();
    }
}
