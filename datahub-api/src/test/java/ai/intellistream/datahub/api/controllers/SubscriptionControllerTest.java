// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.controllers;

import ai.intellistream.datahub.api.responses.DataWrapper;
import ai.intellistream.datahub.api.services.SubscriptionService;
import ai.intellistream.datahub.models.datafilters.FilterDefaults;
import ai.intellistream.datahub.subscription.Subscription;
import ai.intellistream.datahub.subscription.SubscriptionRetriever;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Web-layer tests for {@code GET /subscriptions} — the no-body listing, which exists because
 * asking "what subscriptions do I have" should not require composing a POST body. Every other
 * collection has one ({@code GET /timeseries}, {@code GET /labels}, {@code GET /units}); this is
 * that endpoint held to the same defaults as {@code POST /subscriptions/filter}.
 *
 * <p>Stand-alone {@link MockMvc} rather than {@code @WebMvcTest}, for the reasons
 * {@link TimeseriesControllerTest} states: booting the context would pull in the OAuth2 resource
 * server and the Pulsar/Vault bean graph, none of which a unit test has.
 */
class SubscriptionControllerTest {

    private final SubscriptionService subscriptionService = mock(SubscriptionService.class);
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new SubscriptionController(subscriptionService)).build();
        when(subscriptionService.filter(any())).thenReturn(new DataWrapper<Subscription>());
    }

    @Test
    void noLimitUsesTheSamePageSizeAsTheFilterEndpoint() throws Exception {
        mockMvc.perform(get("/subscriptions")).andExpect(status().isOk());

        assertEquals(FilterDefaults.DEFAULT_LIMIT, capturedRetriever().getLimit());
    }

    @Test
    void anExplicitLimitIsHonoured() throws Exception {
        mockMvc.perform(get("/subscriptions").param("limit", "42")).andExpect(status().isOk());

        assertEquals(42, capturedRetriever().getLimit());
    }

    /** Zero and negative mean "you decide" on the filter endpoint; they must mean it here too. */
    @Test
    void aNonPositiveLimitFallsBackToTheDefaultRatherThanReturningNothing() throws Exception {
        mockMvc.perform(get("/subscriptions").param("limit", "0")).andExpect(status().isOk());

        assertEquals(FilterDefaults.DEFAULT_LIMIT, capturedRetriever().getLimit());
    }

    @Test
    void aLimitAboveTheMaximumIsRejected() throws Exception {
        mockMvc.perform(get("/subscriptions").param("limit", "10001"))
                .andExpect(status().isBadRequest());
    }

    /**
     * A cursor is only usable by an endpoint that accepts one back. Handing one out here would
     * invite a paging loop that never advances.
     */
    @Test
    void theListingNeverHandsBackACursor() throws Exception {
        DataWrapper<Subscription> paged = new DataWrapper<Subscription>();
        paged.setItems(List.of(new Subscription()));
        paged.setNextCursor("dj F8Y3JlYXRlZFRpbWU");
        when(subscriptionService.filter(any())).thenReturn(paged);

        mockMvc.perform(get("/subscriptions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nextCursor").doesNotExist());
    }

    private SubscriptionRetriever capturedRetriever() {
        ArgumentCaptor<SubscriptionRetriever> captor = ArgumentCaptor.forClass(SubscriptionRetriever.class);
        verify(subscriptionService).filter(captor.capture());
        return captor.getValue();
    }
}
