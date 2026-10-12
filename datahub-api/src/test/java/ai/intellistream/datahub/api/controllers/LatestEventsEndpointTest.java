// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.controllers;

import ai.intellistream.datahub.api.controllers.errors.BadRequestExceptionHandler;
import ai.intellistream.datahub.api.datasecurity.DataSecurity;
import ai.intellistream.datahub.api.datasecurity.DatasetClosureService;
import ai.intellistream.datahub.api.services.EventService;
import ai.intellistream.datahub.api.services.IngestQuotaService;
import ai.intellistream.datahub.clickhouse.ClickHouseEventService;
import ai.intellistream.datahub.repositories.event.EventDimensionRepository;
import ai.intellistream.datahub.repositories.node.DataSetRepository;
import ai.intellistream.datahub.repositories.node.NodeRepository;
import ai.intellistream.datahub.services.KVRocksService;
import jakarta.validation.Validator;
import org.apache.pulsar.client.api.Producer;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code POST /events/latest} refuses a status inside the filter with a 400 the caller sees, rather
 * than answering a different question. Through MockMvc and the real service, so the refusal has to
 * travel the path it takes in production.
 */
class LatestEventsEndpointTest {

    @Test
    void aStatusInsideTheFilterIsA400() throws Exception {
        ClickHouseEventService clickHouse = mock(ClickHouseEventService.class);
        EventService service = new EventService(mock(NodeRepository.class), clickHouse,
                mock(KVRocksService.class), mock(Validator.class), mock(ApplicationEventPublisher.class),
                mock(Producer.class), mock(DataSecurity.class), mock(EventDimensionRepository.class),
                mock(DataSetRepository.class), mock(DatasetClosureService.class),
                mock(IngestQuotaService.class));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new EventController(service, mock(Validator.class)))
                .setControllerAdvice(new BadRequestExceptionHandler())
                .build();

        mvc.perform(post("/events/latest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"filter\":{\"type\":[\"flow\"],\"status\":[\"open\"]}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail", containsString("top-level status")));
        verifyNoInteractions(clickHouse);
    }
}
