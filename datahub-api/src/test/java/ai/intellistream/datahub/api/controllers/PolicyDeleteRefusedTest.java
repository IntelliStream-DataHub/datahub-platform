// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.controllers;

import ai.intellistream.datahub.api.controllers.errors.Problems;
import ai.intellistream.datahub.api.controllers.errors.ResourceDeleteException;
import ai.intellistream.datahub.api.controllers.errors.ResourceDeleteExceptionHandler;
import ai.intellistream.datahub.api.services.PolicyCheckService;
import ai.intellistream.datahub.api.services.PolicyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code POST /policies/delete} reaches the shared resource-delete pipeline through
 * {@code PolicyService.deletePolicies}, so it can be refused by the same graph guards as every
 * other delete — but it was the one delete endpoint with no {@code ResourceDeleteException}
 * {@code catch}, so a refusal came back as a bare 500 naming nothing.
 *
 * <p>Nobody wrote a matching catch for it; handling the exception in an advice is what fixes it,
 * and this pins that the fix reaches the endpoint that never had the local workaround.
 */
class PolicyDeleteRefusedTest {

    private PolicyService policyService;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        policyService = mock(PolicyService.class);
        PolicyController controller = new PolicyController(policyService, mock(PolicyCheckService.class));

        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new ResourceDeleteExceptionHandler())
                .build();
    }

    @Test
    void delete_wouldStrandNodes_returns409_notABare500() throws Exception {
        Mockito.doThrow(new ResourceDeleteException(Problems.WOULD_STRAND,
                        "Deleting this selection would disconnect resource(s) [klp_valve_v9]"
                                + " from the graph root. Include them in the deletion or keep a connecting path.",
                        List.of(Map.of("externalId", "klp_valve_v9"))))
                .when(policyService).deletePolicies(any());

        mvc.perform(post("/policies/delete")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"id\":341}]}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("https://intellistream.ai/errors/would-strand"))
                .andExpect(jsonPath("$.blockedBy[0].externalId").value("klp_valve_v9"));
    }
}
