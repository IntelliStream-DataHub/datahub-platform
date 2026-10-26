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

/** The section's views hang off {@code /agents} by query parameter, as Flows does; the gate is agents, not flows. */
class AgentsControllerTest {

    private MockMvc mvc(boolean agents) {
        FlowAccess access = mock(FlowAccess.class);
        when(access.agentsAvailable()).thenReturn(agents);
        return MockMvcBuilders.standaloneSetup(new AgentsController(access)).build();
    }

    @Test
    void eachViewHasItsTemplate() throws Exception {
        MockMvc mvc = mvc(true);
        mvc.perform(get("/agents")).andExpect(view().name("agents/index"))
                .andExpect(model().attribute("featureDisabled", false));
        mvc.perform(get("/agents").param("tab", "attention")).andExpect(view().name("agents/attention"));
        mvc.perform(get("/agents").param("new", "")).andExpect(view().name("agents/agent"))
                .andExpect(model().attribute("flowExternalId", ""));
        mvc.perform(get("/agents/pump_watch")).andExpect(view().name("agents/agent"))
                .andExpect(model().attribute("flowExternalId", "pump_watch"));
        mvc.perform(get("/agents/pump_watch/runs/0190a5d2-0000-7000-8000-000000000001"))
                .andExpect(view().name("agents/run"))
                .andExpect(model().attribute("runId", "0190a5d2-0000-7000-8000-000000000001"));
    }

    @Test
    void anAgentCalledAttentionIsAnAgent() throws Exception {
        mvc(true).perform(get("/agents/attention")).andExpect(view().name("agents/agent"))
                .andExpect(model().attribute("flowExternalId", "attention"));
    }

    @Test
    void aTenantWithoutAgentsGetsTheNotice() throws Exception {
        mvc(false).perform(get("/agents")).andExpect(model().attribute("featureDisabled", true));
    }
}
