// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.dhconsole.controllers;

import ai.intellistream.dhconsole.config.FlowAccess;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/**
 * The section's views hang off {@code /flows} by query parameter, so that any flow id, even
 * {@code attention}, is a flow page; the deploy round-trip lands on {@code /flows/<id>}.
 */
class FlowsControllerTest {

    private MockMvc mvc(boolean flows) {
        FlowAccess access = mock(FlowAccess.class);
        when(access.available()).thenReturn(flows);
        return MockMvcBuilders.standaloneSetup(new FlowsController(access)).build();
    }

    @Test
    void eachViewHasItsTemplate() throws Exception {
        MockMvc mvc = mvc(true);
        mvc.perform(get("/flows")).andExpect(view().name("flows/index"))
                .andExpect(model().attribute("featureDisabled", false));
        mvc.perform(get("/flows").param("tab", "attention")).andExpect(view().name("flows/attention"));
        mvc.perform(get("/flows").param("tab", "lookup")).andExpect(view().name("flows/lookup"));
        mvc.perform(get("/flows/temp_hourly")).andExpect(view().name("flows/flow"))
                .andExpect(model().attribute("flowExternalId", "temp_hourly"));
        mvc.perform(get("/flows/temp_hourly/runs/0190a5d2-0000-7000-8000-000000000001"))
                .andExpect(view().name("flows/run"))
                .andExpect(model().attribute("runId", "0190a5d2-0000-7000-8000-000000000001"));
    }

    @Test
    void aFlowCalledAttentionIsAFlow() throws Exception {
        mvc(true).perform(get("/flows/attention")).andExpect(view().name("flows/flow"))
                .andExpect(model().attribute("flowExternalId", "attention"));
    }

    @Test
    void anExternalIdWithDotsIsKeptWhole() throws Exception {
        mvc(true).perform(get("/flows/plant.line1.hourly")).andExpect(view().name("flows/flow"))
                .andExpect(model().attribute("flowExternalId", "plant.line1.hourly"));
    }

    @Test
    void aTenantWithoutFlowsGetsTheNotice() throws Exception {
        mvc(false).perform(get("/flows")).andExpect(model().attribute("featureDisabled", true));
    }
}
