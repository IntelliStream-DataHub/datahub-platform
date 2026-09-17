// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.controllers;

import ai.intellistream.datahub.api.controllers.errors.InvalidDatapointException;
import ai.intellistream.datahub.api.controllers.errors.MessagingUnavailableExceptionHandler;
import ai.intellistream.datahub.api.controllers.errors.InvalidDatapointExceptionHandler;
import ai.intellistream.datahub.api.controllers.errors.InvalidTimestampException;
import ai.intellistream.datahub.api.controllers.errors.InvalidTimestampExceptionHandler;
import ai.intellistream.datahub.api.controllers.errors.ConstraintViolationExceptionHandler;
import ai.intellistream.datahub.api.controllers.errors.DuplicateDataExceptionHandler;
import ai.intellistream.datahub.api.controllers.errors.ConcurrencyExceptionHandler;
import ai.intellistream.datahub.api.controllers.errors.DuplicateDataException;
import ai.intellistream.datahub.api.controllers.errors.ResourceDeleteException;
import ai.intellistream.datahub.api.controllers.errors.ResourceDeleteExceptionHandler;
import ai.intellistream.datahub.api.controllers.errors.ProblemResponseAdvice;
import ai.intellistream.datahub.api.controllers.errors.Problems;
import ai.intellistream.datahub.api.controllers.errors.RequestBodyValidationExceptionHandler;
import org.apache.pulsar.client.api.PulsarClientException;
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
                .setControllerAdvice(new ConcurrencyExceptionHandler(),
                        // The controller no longer catches these; the advices answer them.
                        new DuplicateDataExceptionHandler(),
                        new ConstraintViolationExceptionHandler(),
                        new InvalidDatapointExceptionHandler(),
                        new InvalidTimestampExceptionHandler(),
                        new MessagingUnavailableExceptionHandler(),
                        new RequestBodyValidationExceptionHandler(),
                        // Supplies requestId, retry and docs, exactly as in production.
                        new ProblemResponseAdvice(),
                        new ResourceDeleteExceptionHandler())
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
                // Was $.items[0].externalId — a success-shaped envelope used as an error body.
                .andExpect(jsonPath("$.type").value("https://intellistream.ai/errors/constraint-violation"))
                .andExpect(jsonPath("$.fields[0].field").value("externalId"))
                .andExpect(jsonPath("$.fields[0].message").value("must not be blank"));
    }

    // --- 409: write conflicts ----------------------------------------------------------------

    @Test
    void create_duplicateExternalId_returns409_withDuplicateError() throws Exception {
        when(timeseriesService.save(any())).thenThrow(DuplicateDataException.of(
                "External id already exists.", "externalId", "sensor_temp_room_a"));

        mvc.perform(post("/timeseries/create")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content("""
                                {"items":[{"externalId":"sensor_temp_room_a","name":"Room A temperature","valueType":"FLOAT"}]}"""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("https://intellistream.ai/errors/duplicate"))
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.duplicated[0].externalId").value("sensor_temp_room_a"));
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
                // RFC 9457: the type is the discriminator that ConflictError.cause = "concurrency"
                // used to be, and the status lives on the response rather than inside the body.
                .andExpect(jsonPath("$.type").value("https://intellistream.ai/errors/optimistic-lock"))
                .andExpect(jsonPath("$.title").value("Conflict"))
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.error").doesNotExist());
    }

    // --- 409: delete blocked by a subscription -----------------------------------------------

    @Test
    void delete_timeseriesReferencedBySubscription_returns409_withBlockingSubscriptions() throws Exception {
        // The shared resource-delete pipeline refuses to delete a timeseries still referenced by a
        // subscription and throws ResourceDeleteException carrying the blocking subscription(s).
        // The controller no longer catches it; ResourceDeleteExceptionHandler answers with a
        // problem whose `blockedBy` names the subscription the caller has to remove first.
        Mockito.doThrow(new ResourceDeleteException(Problems.REFERENCED,
                        "Cannot delete resource(s) that are referenced by subscription(s). "
                                + "Remove the subscriptions first.",
                        List.of(Map.of(
                                "subscriptionId", "9",
                                "subscriptionExternalId", "sub_a",
                                "timeseriesId", "5"))))
                .when(timeseriesService).deleteTimeseries(any());

        mvc.perform(post("/timeseries/delete")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"externalId\":\"sensor_temp_room_a\"}]}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("https://intellistream.ai/errors/referenced"))
                .andExpect(jsonPath("$.title").value("Delete refused"))
                .andExpect(jsonPath("$.detail", containsString("subscription")))
                .andExpect(jsonPath("$.blockedBy[0].subscriptionExternalId").value("sub_a"))
                .andExpect(jsonPath("$.blockedBy[0].timeseriesId").value("5"))
                // The old ResponseError envelope is gone, not merely renamed.
                .andExpect(jsonPath("$.error").doesNotExist());
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
        // The service reports no misses -> the endpoint returns 204 with no body.
        Mockito.doReturn(List.of()).when(timeseriesService).insertDatapoints(Mockito.any());

        mvc.perform(post("/timeseries/data")
                        .content(INSERT_DATAPOINTS_BODY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isNoContent());
    }

    @Test
    void insertDataPoints_someTargetsMissing_returns404WithTheSkippedTargets() throws Exception {
        // A partial success: the rest were inserted, and the misses are named so the caller can
        // create them and retry. It used to answer with a DataWrapper whose `items` were error
        // objects — a success-shaped envelope indistinguishable from a listing.
        Mockito.doReturn(List.of(Map.of("externalId", "does_not_exist", "id", "null")))
                .when(timeseriesService).insertDatapoints(Mockito.any());

        mvc.perform(post("/timeseries/data")
                        .content(INSERT_DATAPOINTS_BODY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("https://intellistream.ai/errors/not-found"))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.missing[0].externalId").value("does_not_exist"))
                .andExpect(jsonPath("$.items").doesNotExist());
    }

    @Test
    void insertDataPoints_nullDatapointList_returns400_andNeverReachesService() throws Exception {
        // Omitting `datapoints` bound cleanly and then NPE'd inside the insert — a 500 for what is
        // plainly a malformed request. @NotNull on the field turns it into the documented 400.
        mvc.perform(post("/timeseries/data")
                        .content("""
                                { "items": [ { "externalId": "sensor_temp_room_a" } ] }
                                """)
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value(Problems.VALIDATION_FAILED.toString()))
                .andExpect(jsonPath("$.fields[0].field", containsString("datapoints")));

        verify(timeseriesService, never()).insertDatapoints(Mockito.any());
    }

    @Test
    void insertDataPoints_blankValue_returns400_andNeverReachesService() throws Exception {
        // @NotBlank on DatapointString only bites because `datapoints` carries @Valid, so the
        // cascade reaches the elements. Without it a blank value failed deep inside the insert.
        mvc.perform(post("/timeseries/data")
                        .content("""
                                {
                                  "items": [
                                    {
                                      "externalId": "sensor_temp_room_a",
                                      "datapoints": [ { "timestamp": 1745328000000, "value": "" } ]
                                    }
                                  ]
                                }
                                """)
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value(Problems.VALIDATION_FAILED.toString()));

        verify(timeseriesService, never()).insertDatapoints(Mockito.any());
    }

    @Test
    void insertDataPoints_valueDoesNotMatchTheDeclaredType_returns422() throws Exception {
        // The one case 422 is for. Retrying the same payload can't help, so the caller is told to
        // fix the value rather than send it again — and gets a link to what the types accept.
        Mockito.doThrow(new InvalidDatapointException("Could not parse value: abc to long"))
                .when(timeseriesService).insertDatapoints(Mockito.any());

        mvc.perform(post("/timeseries/data")
                        .content(INSERT_DATAPOINTS_BODY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.type").value(Problems.INVALID_DATAPOINT.toString()))
                .andExpect(jsonPath("$.detail").value("Could not parse value: abc to long"))
                .andExpect(jsonPath("$.docs")
                        .value("https://intellistream.ai/sdk-documentation/reference/timeseries#value-types"))
                // ...and the machine-readable half of the same answer.
                .andExpect(jsonPath("$.retry").value(Problems.RETRY_CHANGE_REQUEST));
    }

    @Test
    void insertDataPoints_badTimestamp_is422WithTheSharedTimestampType() throws Exception {
        // Not invalid-datapoint: a timestamp is refused the same way wherever it is sent, so a
        // caller who has learned this type on a filter bound recognises it here without reading
        // the prose. It used to be a 500 on this path and a 400 on the other two.
        Mockito.doThrow(new InvalidTimestampException(
                        "'last tuesday' is not a valid timestamp. Send epoch milliseconds.", null))
                .when(timeseriesService).insertDatapoints(Mockito.any());

        mvc.perform(post("/timeseries/data")
                        .content(INSERT_DATAPOINTS_BODY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.type").value(Problems.INVALID_TIMESTAMP.toString()))
                .andExpect(jsonPath("$.docs")
                        .value("https://intellistream.ai/sdk-documentation/reference/client#timestamps"))
                .andExpect(jsonPath("$.retry").value(Problems.RETRY_CHANGE_REQUEST));
    }

    @Test
    void insertDataPoints_brokerUnreachable_isNotA422() throws Exception {
        // Pulsar being down is the server's problem, not the payload's. It must not borrow the 422:
        // the Java SDK reads 4xx as terminal, so the datapoints would be neither retried nor
        // spooled to its durable buffer, and an outage would silently drop them.
        Mockito.doThrow(new PulsarClientException("broker unreachable"))
                .when(timeseriesService).insertDatapoints(Mockito.any());

        mvc.perform(post("/timeseries/data")
                        .content(INSERT_DATAPOINTS_BODY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.type").value(Problems.type("messaging-unavailable").toString()))
                // Transient, so the caller is told to send the very same request again.
                .andExpect(jsonPath("$.retry").value(Problems.RETRY_SAME_REQUEST));
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
