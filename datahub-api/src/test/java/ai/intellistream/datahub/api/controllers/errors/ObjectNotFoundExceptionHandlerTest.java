// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.controllers.errors;

import ai.intellistream.datahub.errors.ObjectNotFoundException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The shared 404 for every {@code GET /xyz/{id}}. It built its own body and set no {@code type},
 * so the most common refusal in the API was the one a client could say least about.
 */
class ObjectNotFoundExceptionHandlerTest {

    private final ObjectNotFoundExceptionHandler handler = new ObjectNotFoundExceptionHandler();

    @Test
    void aMissIsTypedLikeEveryOtherRefusal() {
        ProblemDetail problem = handler.handleNotFound(
                new ObjectNotFoundException("Timeseries with id: 42 Not found!"));

        assertThat(problem.getStatus()).isEqualTo(HttpStatus.NOT_FOUND.value());
        assertThat(problem.getType()).hasToString("https://intellistream.ai/errors/not-found");
        assertThat(problem.getTitle()).isEqualTo("Not Found");
        assertThat(problem.getDetail()).isEqualTo("Timeseries with id: 42 Not found!");
    }

    /**
     * about:blank is what {@link ProblemDetail} defaults to, and what this handler sent before.
     * Named here because the status alone cannot tell a miss from any other 404 on the route.
     */
    @Test
    void neverAboutBlank() {
        ProblemDetail problem = handler.handleNotFound(new ObjectNotFoundException("gone"));

        assertThat(problem.getType()).isNotEqualTo(ProblemDetail.forStatus(404).getType());
    }
}
