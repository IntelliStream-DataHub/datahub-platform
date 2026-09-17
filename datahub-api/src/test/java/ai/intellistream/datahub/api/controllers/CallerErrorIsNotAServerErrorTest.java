// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.controllers;

import ai.intellistream.datahub.api.controllers.errors.BadRequestException;
import ai.intellistream.datahub.api.controllers.errors.BadRequestExceptionHandler;
import ai.intellistream.datahub.api.controllers.errors.ProblemResponseAdvice;
import ai.intellistream.datahub.api.controllers.errors.Problems;
import ai.intellistream.datahub.api.services.EventService;
import ai.intellistream.datahub.api.services.PolicyCheckService;
import ai.intellistream.datahub.api.services.PolicyService;
import jakarta.validation.Validator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Two refusals that answered 500, and the reason they are worth pinning.
 *
 * <p>Both were raised as {@link IllegalArgumentException}, which no advice maps. An unmapped
 * runtime exception reaches the error dispatch and is rendered as an internal error — so the caller
 * was told this service had failed and, since 5xx is the retryable half of the split, to send the
 * same request again. Neither can ever succeed unchanged: one batch is too big, the other names no
 * policy.
 *
 * <p>The general shape of the bug is what this guards, not the two instances. Throwing a bare
 * {@code IllegalArgumentException} for a caller's mistake is the easiest way to reintroduce it.
 */
class CallerErrorIsNotAServerErrorTest {

    private static MockMvc mvcFor(Object controller) {
        return MockMvcBuilders.standaloneSetup(controller)
                // ProblemResponseAdvice too, so retry/requestId/docs land as in production.
                .setControllerAdvice(new BadRequestExceptionHandler(), new ProblemResponseAdvice())
                .build();
    }

    @Test
    @DisplayName("more than 10 000 ids is a 400 naming the count, not a 500")
    void tooManyIdsIsA400() throws Exception {
        MockMvc mvc = mvcFor(new EventController(mock(EventService.class), mock(Validator.class)));

        String ids = IntStream.rangeClosed(0, 10_000)
                .mapToObj(i -> "{\"externalId\":\"e" + i + "\"}")
                .collect(Collectors.joining(","));

        mvc.perform(post("/events/byids")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[" + ids + "]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value(Problems.BAD_REQUEST.toString()))
                .andExpect(jsonPath("$.fields[0].field").value("items"))
                // change-request, not same-request: replaying the identical batch cannot work.
                .andExpect(jsonPath("$.retry").value(Problems.RETRY_CHANGE_REQUEST));
    }

    @Test
    @DisplayName("a policy update naming no policy is a 400 from the service's own exception")
    void policyUpdateWithoutAnIdIsA400() throws Exception {
        PolicyService policyService = mock(PolicyService.class);
        Mockito.doThrow(new BadRequestException(
                        "Each policy update must identify the policy by id or externalId.",
                        "externalId", "an id or externalId is required"))
                .when(policyService).updatePolicyNode(any());

        MockMvc mvc = mvcFor(new PolicyController(policyService, mock(PolicyCheckService.class)));

        mvc.perform(post("/policies/update")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"name\":\"no ids here\"}]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields[0].field").value("externalId"));
    }
}
