// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.controllers;

import ai.intellistream.datahub.api.controllers.errors.MalformedCursorExceptionHandler;
import ai.intellistream.datahub.models.paging.MalformedCursorException;
import ai.intellistream.datahub.api.datasecurity.DataSecurity;
import ai.intellistream.datahub.api.services.DataSetService;
import ai.intellistream.datahub.api.services.EventService;
import ai.intellistream.datahub.api.services.ResourceService;
import ai.intellistream.datahub.api.services.TimeseriesService;
import ai.intellistream.datahub.repositories.node.DataSetRepository;
import ai.intellistream.datahub.repositories.node.EdgeRepository;
import ai.intellistream.datahub.repositories.node.PolicyRepository;
import ai.intellistream.datahub.repositories.node.TimeseriesRepository;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A rejected cursor has to reach the caller as a 400.
 *
 * <p>This is the assertion that was missing, and its absence is why the rejection shipped not
 * working. The services were covered — they threw {@link BadRequestException} for a malformed
 * cursor, and unit tests asserted exactly that — but nothing checked what the caller received. No
 * filter endpoint caught the exception and no advice existed, so it escaped: three of them answered
 * <b>200 with an empty body</b> and {@code /datasets/filter}, whose catch-all swallowed it, answered
 * <b>500 "Internal programming error."</b>. A client sending a broken cursor was told its request
 * had succeeded and matched nothing.
 *
 * <p>Testing the throw was testing the half that was already right. These go through MockMvc so the
 * exception has to travel the same path it does in production — controller, advice, response.
 */
class FilterCursorRejectionTest {

    private static MalformedCursorException malformedCursor() {
        return new MalformedCursorException(
                "The supplied cursor cannot be read because it is not valid base64url.");
    }

    private static MockMvc mvcFor(Object controller) {
        return MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new MalformedCursorExceptionHandler())
                .build();
    }

    private void assertRejects(MockMvc mvc, String path) throws Exception {
        mvc.perform(post(path)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cursor\":\"!!not-a-cursor!!\"}"))
                .andExpect(status().isBadRequest())
                // RFC 9457: a stable type a client can branch on, plus the human detail.
                .andExpect(jsonPath("$.type").value(MalformedCursorExceptionHandler.PROBLEM_TYPE))
                .andExpect(jsonPath("$.title").value("Malformed cursor"))
                .andExpect(jsonPath("$.detail", containsString("cursor")));
    }

    @Test
    void datasetsFilterRejectsAMalformedCursor() throws Exception {
        DataSetService service = mock(DataSetService.class);
        when(service.filter(any())).thenThrow(malformedCursor());
        // The catch-all in this controller used to turn the rejection into a 500.
        assertRejects(mvcFor(new DataSetController(service, mock(ResourceService.class),
                mock(DataSecurity.class), mock(Validator.class), mock(DataSetRepository.class),
                mock(PolicyRepository.class))), "/datasets/filter");
    }

    @Test
    void resourcesFilterRejectsAMalformedCursor() throws Exception {
        ResourceService service = mock(ResourceService.class);
        when(service.filter(any())).thenThrow(malformedCursor());
        assertRejects(mvcFor(new ResourceController(service, mock(Validator.class))), "/resources/filter");
    }

    @Test
    void timeseriesFilterRejectsAMalformedCursor() throws Exception {
        TimeseriesService service = mock(TimeseriesService.class);
        when(service.filter(any())).thenThrow(malformedCursor());
        assertRejects(mvcFor(new TimeseriesController(service, mock(EdgeRepository.class),
                mock(TimeseriesRepository.class))), "/timeseries/filter");
    }

    @Test
    void eventsFilterRejectsAMalformedCursor() throws Exception {
        EventService service = mock(EventService.class);
        when(service.filter(any())).thenThrow(malformedCursor());
        assertRejects(mvcFor(new EventController(service, mock(Validator.class))), "/events/filter");
    }
}
