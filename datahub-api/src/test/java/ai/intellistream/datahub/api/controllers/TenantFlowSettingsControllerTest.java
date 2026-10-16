// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.controllers;

import ai.intellistream.datahub.api.controllers.errors.AccessDeniedExceptionHandler;
import ai.intellistream.datahub.api.datasecurity.OrgGroupResolver;
import ai.intellistream.datahub.api.datasecurity.SettingsSecurity;
import ai.intellistream.datahub.api.services.TenantSettingsService;
import ai.intellistream.datahub.models.tenant.TenantFlowClientForm;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The flow credentials are guarded by their own scope: a grant on the model settings, or read on
 * flows, does not reach them, and a refused request never reaches the service that holds the Vault
 * credential. A write answers 204 with no body: write does not imply read, so it reveals no names.
 */
class TenantFlowSettingsControllerTest {

    private final TenantSettingsService service = mock(TenantSettingsService.class);

    private MockMvc mvcGranting(String... groups) {
        OrgGroupResolver resolver = mock(OrgGroupResolver.class);
        when(resolver.groupsForCurrentCaller()).thenReturn(List.of(groups));
        return MockMvcBuilders
                .standaloneSetup(new TenantSettingsController(service, new SettingsSecurity(resolver)))
                .setControllerAdvice(new AccessDeniedExceptionHandler())
                .build();
    }

    @Test
    void anotherScopesGrantDoesNotReachTheFlowCredentials() throws Exception {
        MockMvc mvc = mvcGranting("/settings/llm/read", "/settings/llm/write");

        mvc.perform(get("/tenant/settings/flow")).andExpect(status().isForbidden());
        mvc.perform(put("/tenant/settings/flow/clients/ingest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clientId\":\"datahub-ingest\",\"clientSecret\":\"s\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(delete("/tenant/settings/flow/secrets/pw")).andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    @Test
    void readingDoesNotGrantWriting() throws Exception {
        MockMvc mvc = mvcGranting("/settings/flow/read");

        mvc.perform(put("/tenant/settings/flow/secrets/pw")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\":\"hunter2\"}"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    @Test
    void aWriteGrantRegistersAClientAndLearnsNothingBack() throws Exception {
        MockMvc mvc = mvcGranting("/settings/flow/write");

        mvc.perform(put("/tenant/settings/flow/clients/ingest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clientId\":\"datahub-ingest\",\"clientSecret\":\"s\"}"))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));
        verify(service).putFlowClient("ingest", new TenantFlowClientForm("datahub-ingest", "s"));
    }
}
