// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.controllers;

import ai.intellistream.datahub.api.responses.DataWrapper;
import ai.intellistream.datahub.jpa.domains.Label;
import ai.intellistream.datahub.repositories.label.LabelRepository;
import ai.intellistream.datahub.services.LabelService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Web-layer tests for {@link LabelController#create}. The create endpoint previously had no
 * {@code @Valid}, so a blank/missing label name slipped past validation and a null name NPE'd in
 * {@code IdGenerator.xxHash(getName())} before any service ran. These lock in the 400 behaviour.
 *
 * <p>Stand-alone {@link MockMvc} with the collaborators mocked, mirroring {@link EdgeControllerTest}.
 */
class LabelControllerTest {

    private LabelService labelService;
    private LabelRepository labelRepository;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        labelService = mock(LabelService.class);
        labelRepository = mock(LabelRepository.class);

        LabelController controller = new LabelController(labelService, labelRepository);

        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();

        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setValidator(validator)
                .build();
    }

    @Test
    void create_blankName_returns400_andNeverReachesService() throws Exception {
        mvc.perform(post("/labels/create")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"name\":\"\"}]}"))
                .andExpect(status().isBadRequest());

        verify(labelService, never()).createLabels(any());
    }

    @Test
    void create_missingName_returns400_andNeverReachesService() throws Exception {
        mvc.perform(post("/labels/create")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"description\":\"no name\"}]}"))
                .andExpect(status().isBadRequest());

        verify(labelService, never()).createLabels(any());
    }

    @Test
    void create_validName_returns200() throws Exception {
        Label saved = new Label();
        saved.setName("pump_station");
        when(labelRepository.findAllByHashList(any())).thenReturn(List.of());
        when(labelService.createLabels(any())).thenReturn(List.of(saved));

        mvc.perform(post("/labels/create")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"name\":\"pump station\"}]}"))
                .andExpect(status().isOk());

        verify(labelService).createLabels(any());
    }
}
