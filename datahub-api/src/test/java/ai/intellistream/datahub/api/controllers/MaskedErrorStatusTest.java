// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.controllers;

import ai.intellistream.datahub.api.controllers.errors.BadRequestError;
import ai.intellistream.datahub.api.controllers.errors.ResourceDeleteException;
import ai.intellistream.datahub.api.services.PolicyCheckService;
import ai.intellistream.datahub.api.services.PolicyService;
import ai.intellistream.datahub.api.services.SubscriptionService;
import ai.intellistream.datahub.errors.ResponseError;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Two endpoints whose catch-all turns a verdict into a bodyless 500.
 *
 * <p>A 500 tells the caller the server broke and the request is worth retrying. Both of these are
 * the opposite: the request will fail identically until the caller changes something. A denial is
 * the caller's to fix by acquiring a grant; a refused delete is theirs to fix by removing what
 * still depends on the target. Neither is discoverable from "Internal Server Error" with no body.
 *
 * <p>These are the only two sites where a denial can actually reach a catch-all — the other
 * fourteen call services that narrow by readable dataset rather than asserting, so they never raise
 * {@link AccessDeniedException} in the first place.
 */
class MaskedErrorStatusTest {

    // --- POST /policies/delete ----------------------------------------------------------------

    private PolicyService policyService;

    private MockMvc policyMvc() {
        policyService = mock(PolicyService.class);
        return MockMvcBuilders
                .standaloneSetup(new PolicyController(policyService, mock(PolicyCheckService.class)))
                .build();
    }

    @Test
    @DisplayName("a policy delete the graph refuses is a 400 naming the blockers, not a 500")
    void policyDelete_refusedByTheGraph_is400() throws Exception {
        MockMvc mvc = policyMvc();
        // PolicyService.deletePolicies goes through the shared resource-delete pipeline, so it is
        // subject to the same stranded-node guard as every other delete. It was the one delete
        // endpoint with no ResourceDeleteException catch, so the refusal met catch (Exception).
        var error = new ResponseError<BadRequestError>();
        error.setError(new BadRequestError()
                .setMessage("Deleting this selection would disconnect resource(s) [klp_valve_v9] "
                        + "from the graph root.")
                .addFieldError("externalId", "klp_valve_v9"));
        Mockito.doThrow(new ResourceDeleteException(error))
                .when(policyService).deletePolicies(any());

        mvc.perform(post("/policies/delete")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"id\":341}]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.fields[0].externalId").value("klp_valve_v9"));
    }

    @Test
    @DisplayName("a denied policy delete reaches the 403 handler instead of being masked")
    void policyDelete_denied_propagates() throws Exception {
        MockMvc mvc = policyMvc();
        Mockito.doThrow(new AccessDeniedException("no write access to data set 12"))
                .when(policyService).deletePolicies(any());

        // The denial must escape the handler so Spring Security answers 403. MockMvc may wrap it
        // in a ServletException, so accept either shape — the same assertion EdgeControllerTest
        // makes for its own delete.
        Exception thrown = assertThrows(Exception.class, () ->
                mvc.perform(post("/policies/delete")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"id\":341}]}")));
        assertTrue(thrown instanceof AccessDeniedException
                        || thrown.getCause() instanceof AccessDeniedException,
                "expected the AccessDeniedException to propagate, got " + thrown);
    }

    // --- POST /subscriptions/create -----------------------------------------------------------

    @Test
    @DisplayName("a denied subscription create reaches the 403 handler instead of being masked")
    void subscriptionCreate_denied_propagates() throws Exception {
        SubscriptionService subscriptionService = mock(SubscriptionService.class);
        MockMvc mvc = MockMvcBuilders
                .standaloneSetup(new SubscriptionController(subscriptionService))
                .build();
        // SubscriptionService.create calls dataSecurity.assertCanRead on every timeseries named in
        // the body, so subscribing to a series in an unreadable data set is a denial — which the
        // broad RuntimeException catch reported as a server fault.
        Mockito.doThrow(new AccessDeniedException("no read access to data set 12"))
                .when(subscriptionService).create(any());

        Exception thrown = assertThrows(Exception.class, () ->
                mvc.perform(post("/subscriptions/create")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"externalId\":\"sub_a\",\"timeseries\":[{\"externalId\":\"t1\"}]}]}")));
        assertTrue(thrown instanceof AccessDeniedException
                        || thrown.getCause() instanceof AccessDeniedException,
                "expected the AccessDeniedException to propagate, got " + thrown);
    }
}
