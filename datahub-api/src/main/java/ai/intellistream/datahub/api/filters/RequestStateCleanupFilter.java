// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.filters;

import ai.intellistream.datahub.api.datasecurity.DatasetPermissionsResolver;
import ai.intellistream.datahub.api.policy.PolicyWarningContext;
import ai.intellistream.datahub.tenant.TenantContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Clears every request-scoped {@link ThreadLocal} at the end of the request.
 *
 * <p>Tomcat reuses worker threads, so anything left behind is read by whichever request lands on
 * that thread next. Both values cleared here are set during authentication and are caller-specific:
 * <ul>
 *   <li>{@link TenantContext} — set by {@code SecurityConfig.OrganizationValidator} from the JWT.
 *       Leaking it would point a request at another tenant's database.</li>
 *   <li>The memoised {@code DatasetPermissions} — resolved once per request by
 *       {@link DatasetPermissionsResolver}. Leaking it would hand one caller another caller's
 *       dataset grants.</li>
 *   <li>The policy warnings raised during a write ({@link PolicyWarningContext}), carried from the
 *       service that raised them to the response envelope. Leaking them would report one caller's
 *       external ids to the next request on the thread.</li>
 * </ul>
 * The first two are authorization-critical and the third leaks data across callers, so the clears
 * live in a {@code finally} and this filter is registered at {@code HIGHEST_PRECEDENCE}: outermost,
 * so it runs after the security filter chain that populates them, and still runs when the request
 * failed.
 *
 * <p>Formerly {@code TenantContextCleanupFilter}, renamed when it took on the second ThreadLocal.
 */
public class RequestStateCleanupFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            chain.doFilter(request, response);
        } finally {
            TenantContext.clear();
            DatasetPermissionsResolver.clearCurrent();
            PolicyWarningContext.clear();
        }
    }
}
