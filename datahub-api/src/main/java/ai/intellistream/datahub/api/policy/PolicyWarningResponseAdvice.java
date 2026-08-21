// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.policy;

import ai.intellistream.datahub.api.responses.DataWrapper;
import ai.intellistream.datahub.api.responses.GraphDataWrapper;
import ai.intellistream.datahub.models.policy.PolicyWarning;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

import java.util.List;

/**
 * Attaches any policy warnings raised during a request to the response envelope on the way out.
 *
 * <p>Done here rather than in each controller so every write endpoint reports warnings the same
 * way, including the ones that build their own envelope from a service result
 * ({@code DataSetController}, and anything added later). The alternative — threading a warnings
 * list back through every create/update signature — would touch dozens of methods to carry
 * something that is empty on almost every request.
 *
 * <p>Never overwrites warnings a service already set, so an explicit assignment wins.
 *
 * <p>The field is left absent when there is nothing to report (see {@code DataWrapper.warnings}),
 * so a response with no warnings is byte-identical to what clients see today.
 */
@RestControllerAdvice
public class PolicyWarningResponseAdvice implements ResponseBodyAdvice<Object> {

    @Override
    public boolean supports(MethodParameter returnType, Class converterType) {
        // Cannot inspect the actual body here, only the declared return type, which is frequently
        // ResponseEntity<?>. So opt in broadly and do the real check in beforeBodyWrite, which is a
        // cheap instanceof against a ThreadLocal that is empty on almost every request.
        return true;
    }

    @Override
    public Object beforeBodyWrite(Object body, MethodParameter returnType, MediaType contentType,
                                  Class converterType, ServerHttpRequest request, ServerHttpResponse response) {
        if (!(body instanceof DataWrapper<?>) && !(body instanceof GraphDataWrapper<?, ?>)) {
            return body;
        }
        List<PolicyWarning> warnings = PolicyWarningContext.current();
        if (warnings.isEmpty()) {
            return body;
        }
        if (body instanceof DataWrapper<?> wrapper) {
            if (wrapper.getWarnings() == null) {
                wrapper.setWarnings(warnings);
            }
        } else if (body instanceof GraphDataWrapper<?, ?> wrapper && wrapper.getWarnings() == null) {
            wrapper.setWarnings(warnings);
        }
        return body;
    }
}
