// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.controllers;

import ai.intellistream.datahub.api.datasecurity.DataSecurity;
import ai.intellistream.datahub.api.responses.DataWrapper;
import ai.intellistream.datahub.api.services.DataSetService;
import ai.intellistream.datahub.api.services.ResourceService;
import ai.intellistream.datahub.models.DataSetModel;
import ai.intellistream.datahub.models.DataSetRetreiver;
import ai.intellistream.datahub.models.datafilters.FilterDefaults;
import ai.intellistream.datahub.repositories.node.DataSetRepository;
import ai.intellistream.datahub.repositories.node.PolicyRepository;
import jakarta.validation.Validator;
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
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Web-layer tests for {@code GET /datasets} — the no-body listing that replaced
 * {@code POST /datasets/list}.
 *
 * <p>Stand-alone {@link MockMvc} rather than {@code @WebMvcTest}, for the reasons
 * {@link TimeseriesControllerTest} states: booting the context would pull in the OAuth2 resource
 * server and the Pulsar/Vault bean graph, none of which a unit test has.
 */
class DataSetControllerTest {

    private final DataSetService dataSetService = mock(DataSetService.class);
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        DataSetController controller = new DataSetController(
                dataSetService,
                mock(ResourceService.class),
                mock(DataSecurity.class),
                mock(Validator.class),
                mock(DataSetRepository.class),
                mock(PolicyRepository.class));
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
        when(dataSetService.filter(any())).thenReturn(new DataWrapper<DataSetModel>());
    }

    @Test
    void noLimitUsesTheSamePageSizeAsTheFilterEndpoint() throws Exception {
        mockMvc.perform(get("/datasets")).andExpect(status().isOk());

        assertEquals(FilterDefaults.DEFAULT_LIMIT, capturedRetriever().getLimit());
    }

    @Test
    void anExplicitLimitIsHonoured() throws Exception {
        mockMvc.perform(get("/datasets").param("limit", "42")).andExpect(status().isOk());

        assertEquals(42, capturedRetriever().getLimit());
    }

    /** Zero and negative mean "you decide" on the filter endpoint; they must mean it here too. */
    @Test
    void aNonPositiveLimitFallsBackToTheDefaultRatherThanReturningNothing() throws Exception {
        mockMvc.perform(get("/datasets").param("limit", "0")).andExpect(status().isOk());

        assertEquals(FilterDefaults.DEFAULT_LIMIT, capturedRetriever().getLimit());
    }

    @Test
    void aLimitAboveTheMaximumIsRejected() throws Exception {
        mockMvc.perform(get("/datasets").param("limit", "10001"))
                .andExpect(status().isBadRequest());
    }

    /**
     * A cursor is only usable by an endpoint that accepts one back. Handing one out here would
     * invite a paging loop that never advances; criteria and paging live on {@code /filter}.
     */
    @Test
    void theListingNeverHandsBackACursor() throws Exception {
        DataWrapper<DataSetModel> paged = new DataWrapper<DataSetModel>();
        paged.setItems(List.of(new DataSetModel()));
        paged.setNextCursor("dj F8Y3JlYXRlZFRpbWU");
        when(dataSetService.filter(any())).thenReturn(paged);

        mockMvc.perform(get("/datasets"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nextCursor").doesNotExist());
    }

    /**
     * The alias is gone, not merely deprecated — consolidating the two names is the point of the
     * change.
     *
     * <p>405 rather than 404, and that is worth pinning rather than glossing: with the literal
     * {@code /datasets/list} mapping removed, the path is matched by {@code GET /datasets/{id}},
     * which accepts the path and refuses the verb. So a stale caller gets "not that method here"
     * instead of "no such thing", and — the part that matters — nothing reaches the service.
     * {@link DataSetRoutePrecedenceTest} covers the other side of that same {@code {id}} greediness.
     */
    @Test
    void thePostListAliasIsGone() throws Exception {
        mockMvc.perform(post("/datasets/list")
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isMethodNotAllowed());

        verifyNoInteractions(dataSetService);
    }

    private DataSetRetreiver capturedRetriever() {
        ArgumentCaptor<DataSetRetreiver> captor = ArgumentCaptor.forClass(DataSetRetreiver.class);
        verify(dataSetService).filter(captor.capture());
        return captor.getValue();
    }
}
