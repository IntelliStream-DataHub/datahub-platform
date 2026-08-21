// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.controllers.errors;

import ai.intellistream.datahub.tenant.UnknownTenantException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;

/**
 * Translates {@link UnknownTenantException} into an RFC 9457 {@code application/problem+json}
 * <strong>403</strong>.
 *
 * <p>403, not 500: the token is valid and the caller is who they say they are, but this deployment
 * holds no tenant record for their organization, so there is no database to serve them from. Left
 * unhandled it surfaced as a bodyless 500 — indistinguishable from the api being broken, when the
 * actual answer is "that org was never onboarded here, or was removed".
 *
 * <p>And not 503 either, unlike the sibling {@code UserInfoUnavailableExceptionHandler} and the
 * not-yet-provisioned branch of {@code TenantProvisioningFilter}: those clear on their own, this
 * one needs an operator to add the tenant. Retrying never helps.
 *
 * <p>This is a backstop. {@code TenantProvisioningFilter} refuses these requests before they reach
 * a controller, which matters because several controllers wrap their body in a blanket
 * {@code catch (RuntimeException)} that would flatten this into a 500 before it ever reached an
 * advice. What still arrives here are the paths with no filter in front of them.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
@Slf4j
public class UnknownTenantExceptionHandler {

    @ExceptionHandler(UnknownTenantException.class)
    public ProblemDetail handleUnknownTenant(UnknownTenantException ex) {
        log.warn("Refusing request for unknown tenant {}", ex.getTenantId());

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.FORBIDDEN,
                "Unknown organization: this deployment has no tenant for the organization in your "
                        + "token. Retrying will not help — the organization has to be onboarded.");
        problem.setTitle("Forbidden");
        problem.setType(URI.create("https://intellistream.ai/errors/unknown-tenant"));
        problem.setProperty("organizationId", ex.getTenantId());
        return problem;
    }
}
