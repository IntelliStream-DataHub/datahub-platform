// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.controllers.errors;

import ai.intellistream.datahub.api.filters.RequestIdFilter;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

/** Every problem leaving through MVC gets requestId and retry, and is labelled application/problem+json. */
@RestControllerAdvice
public class ProblemResponseAdvice implements ResponseBodyAdvice<Object> {

    @Override
    public boolean supports(MethodParameter returnType, Class converterType) {
        // The declared type is often ResponseEntity<?>; the real check is on the body below.
        return true;
    }

    @Override
    public Object beforeBodyWrite(Object body, MethodParameter returnType, MediaType contentType,
                                  Class converterType, ServerHttpRequest request, ServerHttpResponse response) {
        if (!(body instanceof ProblemDetail problem)) {
            return body;
        }
        String requestId = request instanceof ServletServerHttpRequest servlet
                ? RequestIdFilter.current(servlet.getServletRequest())
                : null;
        Problems.decorate(problem, requestId);
        // A handler's produces = application/json would otherwise label the problem as plain JSON.
        if (contentType != null && MediaType.APPLICATION_JSON.isCompatibleWith(contentType)) {
            response.getHeaders().setContentType(MediaType.APPLICATION_PROBLEM_JSON);
        }
        return body;
    }
}
