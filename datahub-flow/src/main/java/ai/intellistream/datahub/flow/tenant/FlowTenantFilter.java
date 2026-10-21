// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.flow.tenant;

import ai.intellistream.datahub.tenant.Tenant;
import ai.intellistream.datahub.tenant.TenantConfigService;
import ai.intellistream.datahub.tenant.TenantContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;

/**
 * Lets a request through only for a tenant that may use flows and whose database is ready for them.
 * Runs inside the security chain after authorization, where {@code OrganizationValidator} has set
 * {@link TenantContext} from the token.
 *
 * <ul>
 *   <li><b>403 {@code unknown-tenant}</b> — no tenant record for the token's organization. Only an
 *       operator can fix it.</li>
 *   <li><b>403 {@code feature-disabled}</b> — the tenant exists but its {@code flow} flag is off, as
 *       the api answers for a tenant without files.</li>
 *   <li><b>503 {@code tenant-provisioning}</b> — the flag is on but the flow tables are not
 *       there yet (see {@link FlowSchemaCheck}); retry after a while.</li>
 * </ul>
 *
 * <p>The problem types are the api's ({@code Problems}), so a client handles both services alike.
 * Every endpoint here is tenant-scoped, so a request with no tenant on the thread is refused rather
 * than let through.
 */
@Slf4j
public class FlowTenantFilter extends OncePerRequestFilter {

    private static final String PROBLEM_BASE = "https://intellistream.ai/errors/";

    private final TenantConfigService configService;
    private final FlowSchemaCheck schemaCheck;
    private final JsonMapper jsonMapper;

    public FlowTenantFilter(TenantConfigService configService, FlowSchemaCheck schemaCheck,
                            JsonMapper jsonMapper) {
        this.configService = configService;
        this.schemaCheck = schemaCheck;
        this.jsonMapper = jsonMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String tenantId = TenantContext.getTenantId();
        Tenant tenant = tenantId == null ? null : configService.getConfig(tenantId);
        if (tenant == null) {
            log.warn("Refusing request for unknown tenant {}. Path: {}", tenantId, request.getRequestURI());
            ProblemDetail problem = problem(HttpStatus.FORBIDDEN, "unknown-tenant",
                    "Unknown organization: this deployment has no tenant for the organization in your "
                            + "token. Retrying will not help, the organization has to be onboarded.");
            problem.setProperty("organizationId", tenantId);
            write(response, problem);
            return;
        }

        if (!tenant.getFeatures().isFlowFeatureEnabled()) {
            ProblemDetail problem = problem(HttpStatus.FORBIDDEN, "feature-disabled",
                    "Flows are not enabled for your organization. An operator can turn them on.");
            problem.setProperty("feature", "flow");
            write(response, problem);
            return;
        }

        if (!schemaCheck.isProvisioned(tenantId)) {
            response.setHeader(HttpHeaders.RETRY_AFTER, "30");
            write(response, problem(HttpStatus.SERVICE_UNAVAILABLE, "tenant-provisioning",
                    "This organization's database is still being prepared for flows. Retry the same "
                            + "request shortly."));
            return;
        }

        chain.doFilter(request, response);
    }

    private static ProblemDetail problem(HttpStatus status, String type, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(URI.create(PROBLEM_BASE + type));
        return problem;
    }

    private void write(HttpServletResponse response, ProblemDetail problem) throws IOException {
        response.setStatus(problem.getStatus());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        jsonMapper.writeValue(response.getOutputStream(), problem);
    }
}
