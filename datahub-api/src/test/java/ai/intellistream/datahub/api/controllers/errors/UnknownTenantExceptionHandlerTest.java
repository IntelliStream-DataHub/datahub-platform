// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.controllers.errors;

import ai.intellistream.datahub.tenant.UnknownTenantException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Backstop for the paths with no filter in front of them. The status is the point: an org this
 * deployment has no record of is a denial, not a fault of the service.
 */
class UnknownTenantExceptionHandlerTest {

    private static final String ORG_ID = "ee798389-f522-4e5a-8560-efd83aec61a8";

    private final UnknownTenantExceptionHandler handler = new UnknownTenantExceptionHandler();

    @Test
    void unknownTenantIsForbiddenNotInternalServerError() {
        ProblemDetail problem = handler.handleUnknownTenant(new UnknownTenantException(ORG_ID));

        assertThat(problem.getStatus()).isEqualTo(HttpStatus.FORBIDDEN.value());
        assertThat(problem.getStatus()).isNotEqualTo(HttpStatus.INTERNAL_SERVER_ERROR.value());
    }

    /** So a client can report which org was refused without parsing the message. */
    @Test
    void echoesTheOrganizationId() {
        ProblemDetail problem = handler.handleUnknownTenant(new UnknownTenantException(ORG_ID));

        assertThat(problem.getProperties()).containsEntry("organizationId", ORG_ID);
        assertThat(problem.getType())
                .hasToString("https://intellistream.ai/errors/unknown-tenant");
    }
}
