// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.controllers;

import ai.intellistream.datahub.api.controllers.errors.ConcurrencyExceptionHandler;
import ai.intellistream.datahub.api.controllers.errors.DuplicateDataException;
import ai.intellistream.datahub.api.controllers.errors.DuplicateError;
import ai.intellistream.datahub.api.controllers.errors.BadRequestError;
import ai.intellistream.datahub.api.controllers.errors.ResourceDeleteException;
import ai.intellistream.datahub.errors.ResponseError;
import ai.intellistream.datahub.api.responses.DataWrapper;
import ai.intellistream.datahub.api.services.TimeseriesService;
import ai.intellistream.datahub.models.IdCollection;
import ai.intellistream.datahub.repositories.node.EdgeRepository;
import ai.intellistream.datahub.repositories.node.TimeseriesRepository;
import ai.intellistream.datahub.timeseries.Timeseries;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.OptimisticLockingFailureException;
import org.mockito.Mockito;

import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Collection;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Web-layer tests for {@link TimeseriesController}. They cover the things the compiler can't:
 * JSON request binding, response serialization, and the controller's mapping of service-layer
 * failures to HTTP status codes (400 for bad/invalid input, 409 for write conflicts).
 *
 * <p>Uses a stand-alone {@link MockMvc} rather than {@code @WebMvcTest}: the service collaborators
 * are mocked, so there is no need to boot the Spring context — which would otherwise load the
 * OAuth2 resource-server {@code SecurityConfig} (its {@code jwtDecoder()} performs live OIDC
 * discovery against a configured issuer) and the Pulsar/Vault bean graph, neither of which is
 * available in a unit test. The {@link ConcurrencyExceptionHandler} {@code @RestControllerAdvice}
 * is registered explicitly so the optimistic-lock → 409 path is exercised end to end.
 */
class TimeseriesControllerTest {

    private TimeseriesService timeseriesService;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        timeseriesService = mock(TimeseriesService.class);
        EdgeRepository edgeRepository = mock(EdgeRepository.class);
        TimeseriesRepository timeseriesRepository = mock(TimeseriesRepository.class);

        TimeseriesController controller =
                new TimeseriesController(timeseriesService, edgeRepository, timeseriesRepository);

        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new ConcurrencyExceptionHandler())
                .build();
    }

    // --- serialization round-trips -----------------------------------------------------------

    @Test
    void findByIdList_bindsRequestBody_andSerializesResponse() throws Exception {
        Timeseries ts = new Timeseries();
        ts.setId(5677892L);
        ts.setExternalId("sensor_temp_room_a");
        ts.setName("Room A temperature");
        DataWrapper<Timeseries> result = new DataWrapper<>();
        result.setItems(List.of(ts));
        when(timeseriesService.byids(any())).thenReturn(result);

        mvc.perform(post("/timeseries/byids")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"id\":5677892}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value(5677892))
                .andExpect(jsonPath("$.items[0].externalId").value("sensor_temp_room_a"))
                .andExpect(jsonPath("$.items[0].name").value("Room A temperature"));

        // Confirm the JSON body actually bound to the request DTO the controller forwarded.
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<IdCollection>> captor = ArgumentCaptor.forClass(Collection.class);
        verify(timeseriesService).byids(captor.capture());
        Collection<IdCollection> sent = captor.getValue();
        assertEquals(1, sent.size());
        assertEquals(5677892L, sent.iterator().next().getId());
    }

    @Test
    void create_bindsRequestBody_andReturns201WithSavedItems() throws Exception {
        // Service echoes back what it "saved", stamping a server-assigned id.
        when(timeseriesService.save(any())).thenAnswer(inv -> {
            DataWrapper<Timeseries> in = inv.getArgument(0);
            in.getItems().forEach(t -> t.setId(42L));
            return in;
        });

        mvc.perform(post("/timeseries/create")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content("""
                                {"items":[{"externalId":"sensor_temp_room_a","name":"Room A temperature","valueType":"FLOAT"}]}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.items[0].id").value(42))
                .andExpect(jsonPath("$.items[0].externalId").value("sensor_temp_room_a"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<DataWrapper<Timeseries>> captor = ArgumentCaptor.forClass(DataWrapper.class);
        verify(timeseriesService).save(captor.capture());
        Timeseries sent = captor.getValue().getItems().iterator().next();
        assertEquals("sensor_temp_room_a", sent.getExternalId());
        assertEquals("Room A temperature", sent.getName());
        assertEquals("float", sent.getValueType()); // setter normalises to lower case
    }

    // --- 400: malformed or invalid input -----------------------------------------------------

    @Test
    void create_malformedJson_returns400_andNeverReachesService() throws Exception {
        mvc.perform(post("/timeseries/create")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[ {\"externalId\": }]}")) // broken JSON
                .andExpect(status().isBadRequest());

        verify(timeseriesService, never()).save(any());
    }

    @Test
    void create_missingRequiredField_returns400_withFieldErrors() throws Exception {
        // A missing required field surfaces from the service as a bean-validation failure.
        // Build the exception (which creates mocks) before opening the stubbing, otherwise the
        // nested mock setup trips Mockito's UnfinishedStubbingException.
        ConstraintViolationException cve = constraintViolation("externalId", "must not be blank");
        when(timeseriesService.save(any())).thenThrow(cve);

        mvc.perform(post("/timeseries/create")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"name\":\"Room A temperature\"}]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.items[0].externalId").value("must not be blank"));
    }

    // --- 409: write conflicts ----------------------------------------------------------------

    @Test
    void create_duplicateExternalId_returns409_withDuplicateError() throws Exception {
        when(timeseriesService.save(any())).thenThrow(new DuplicateDataException(
                DuplicateError.createError("External id already exists.", "sensor_temp_room_a")));

        mvc.perform(post("/timeseries/create")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content("""
                                {"items":[{"externalId":"sensor_temp_room_a","name":"Room A temperature","valueType":"FLOAT"}]}"""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value(409))
                .andExpect(jsonPath("$.error.duplicated[0].externalId").value("sensor_temp_room_a"));
    }

    @Test
    void update_optimisticLockConflict_returns409_viaConcurrencyAdvice() throws Exception {
        when(timeseriesService.updateTimeseries(any(DataWrapper.class)))
                .thenThrow(new OptimisticLockingFailureException("stale version"));

        mvc.perform(post("/timeseries/update")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"externalId\":\"sensor_temp_room_a\"}]}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value(409))
                .andExpect(jsonPath("$.error.cause").value("concurrency"));
    }

    // --- 400: delete blocked by a subscription -----------------------------------------------

    @Test
    void delete_timeseriesReferencedBySubscription_returns400_withBlockingSubscriptions() throws Exception {
        // The shared resource-delete pipeline refuses to delete a timeseries still referenced by a
        // subscription and throws ResourceDeleteException carrying the blocking subscription(s).
        // The controller must map that to 400 with the error body — not let it fall through to 500.
        var err = new BadRequestError();
        err.setMessage("Cannot delete resource(s) that are referenced by subscription(s). "
                + "Remove the subscriptions first.");
        err.getFields().add(Map.of(
                "type", "subscription",
                "subscriptionExternalId", "sub_a",
                "timeseriesId", "5"));
        var resp = new ResponseError<BadRequestError>();
        resp.setError(err);
        Mockito.doThrow(new ResourceDeleteException(resp))
                .when(timeseriesService).deleteTimeseries(any());

        mvc.perform(post("/timeseries/delete")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"externalId\":\"sensor_temp_room_a\"}]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.message", containsString("subscription")))
                .andExpect(jsonPath("$.error.fields[0].subscriptionExternalId").value("sub_a"))
                .andExpect(jsonPath("$.error.fields[0].timeseriesId").value("5"));
    }

    // --- insert data-points ------------------------------------------------------------------

    private static final String INSERT_DATAPOINTS_BODY = """
            {
              "items": [
                {
                  "externalId": "sensor_temp_room_a",
                  "datapoints": [
                    { "timestamp": 1745328000000, "value": "22.4" }
                  ]
                }
              ]
            }
            """;

    @Test
    void insertDataPoints_allTargetsExist_returns204NoContent() throws Exception {
        // The service reports no misses -> empty wrapper -> the endpoint returns 204 with no body.
        DataWrapper<BadRequestError> noMisses = new DataWrapper<>();
        // doReturn form: insertDatapoints returns DataWrapper<?>, whose captured wildcard a plain
        // when(...).thenReturn(concrete) can't satisfy.
        Mockito.doReturn(noMisses).when(timeseriesService).insertDatapoints(Mockito.any());

        mvc.perform(post("/timeseries/data")
                        .content(INSERT_DATAPOINTS_BODY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isNoContent());
    }

    @Test
    void insertDataPoints_someTargetsMissing_returns404WithErrorBody() throws Exception {
        // The service returns not-found timeseries as per-entry errors -> 404 with that body.
        var miss = new BadRequestError();
        miss.setCode(404);
        miss.setMessage("Could not find following timeseries.");
        miss.getFields().add(Map.of("externalId", "does_not_exist", "id", "null"));
        DataWrapper<BadRequestError> misses = new DataWrapper<>();
        misses.getItems().add(miss);
        Mockito.doReturn(misses).when(timeseriesService).insertDatapoints(Mockito.any());

        mvc.perform(post("/timeseries/data")
                        .content(INSERT_DATAPOINTS_BODY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.items[0].code").value(404))
                .andExpect(jsonPath("$.items[0].message")
                        .value("Could not find following timeseries."))
                .andExpect(jsonPath("$.items[0].fields[0].externalId")
                        .value("does_not_exist"));
    }

    private static ConstraintViolationException constraintViolation(String field, String message) {
        // A real Path (toString() can't be stubbed via Mockito) so BuildErrorResponse keys the
        // field error by the property name.
        Path path = new Path() {
            @Override
            public java.util.Iterator<Node> iterator() {
                return java.util.Collections.emptyIterator();
            }

            @Override
            public String toString() {
                return field;
            }
        };
        ConstraintViolation<?> violation = mock(ConstraintViolation.class);
        when(violation.getPropertyPath()).thenReturn(path);
        when(violation.getMessage()).thenReturn(message);
        return new ConstraintViolationException(Set.of(violation));
    }
}
