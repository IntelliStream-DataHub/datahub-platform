// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.controllers;

import ai.intellistream.datahub.api.controllers.errors.BadRequestError;
import ai.intellistream.datahub.api.controllers.errors.BadRequestException;
import ai.intellistream.datahub.api.controllers.errors.ResourceDeleteException;
import ai.intellistream.datahub.api.responses.DataWrapper;
import ai.intellistream.datahub.api.services.EdgeService;
import ai.intellistream.datahub.errors.ResponseError;
import ai.intellistream.datahub.jpa.domains.RelationshipType;
import ai.intellistream.datahub.models.EdgeProxy;
import ai.intellistream.datahub.models.RelForm;
import ai.intellistream.datahub.repositories.node.RelationshipTypeRepository;
import ai.intellistream.datahub.resource.RelTypeForm;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Web-layer tests for {@link EdgeController#createRelationTypes} and
 * {@link EdgeController#create}. The relationship-type ones lock in the fix for the blank/null
 * relationship-type bug: a blank or whitespace-only name must be rejected with a 400 (and must
 * never reach the service), and a name that normalises away to nothing must surface as a 400
 * warning rather than a 500. The edge-create ones cover the status mapping that separates "your
 * request was wrong" (400) from "that edge is already there" (409).
 *
 * <p>Uses a stand-alone {@link MockMvc} with the services mocked — same rationale as
 * {@link TimeseriesControllerTest}: no Spring context, so the OAuth2/Pulsar/Vault bean graph is
 * not required. A {@link LocalValidatorFactoryBean} is wired in explicitly so the {@code @Valid}
 * bean validation on the request body actually runs.
 */
class EdgeControllerTest {

    private EdgeService edgeService;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        edgeService = mock(EdgeService.class);
        RelationshipTypeRepository relationshipTypeRepository = mock(RelationshipTypeRepository.class);

        EdgeController controller = new EdgeController(edgeService, relationshipTypeRepository);

        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();

        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setValidator(validator)
                .build();
    }

    @Test
    void createTypes_blankName_returns400_andNeverReachesService() throws Exception {
        mvc.perform(post("/edges/types/create")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"name\":\"\"}]}"))
                .andExpect(status().isBadRequest());

        verify(edgeService, never()).createRelationshipTypes(any());
    }

    @Test
    void createTypes_whitespaceName_returns400_andNeverReachesService() throws Exception {
        mvc.perform(post("/edges/types/create")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"name\":\"   \"}]}"))
                .andExpect(status().isBadRequest());

        verify(edgeService, never()).createRelationshipTypes(any());
    }

    @Test
    void createTypes_missingName_returns400_andNeverReachesService() throws Exception {
        mvc.perform(post("/edges/types/create")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"description\":\"no name supplied\"}]}"))
                .andExpect(status().isBadRequest());

        verify(edgeService, never()).createRelationshipTypes(any());
    }

    @Test
    void createTypes_nameNormalisesToNothing_returns400_viaServiceGuard() throws Exception {
        // "@#$" is not blank, so it passes @NotBlank, but RelationshipType.setName rejects it
        // because it has no letter or digit. The controller must map that to a 400, not a 500.
        when(edgeService.createRelationshipTypes(any()))
                .thenThrow(new IllegalArgumentException("Relationship type name must not be blank"));

        mvc.perform(post("/edges/types/create")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"name\":\"@#$\"}]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.fields[0].name").value("Relationship type name must not be blank"));
    }

    @Test
    void createTypes_validName_returns200_normalisesAndPersists() throws Exception {
        RelationshipType saved = new RelationshipType();
        saved.setName("FLOWS_TO");
        when(edgeService.createRelationshipTypes(any())).thenReturn(List.of(saved));

        mvc.perform(post("/edges/types/create")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"name\":\"flows to\"}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].name").value("FLOWS_TO"));

        // The bound form normalised the human-readable name to SCREAMING_SNAKE_CASE before the
        // controller forwarded it to the service.
        @SuppressWarnings("unchecked")
        ArgumentCaptor<DataWrapper<RelTypeForm>> captor = ArgumentCaptor.forClass(DataWrapper.class);
        verify(edgeService).createRelationshipTypes(captor.capture());
        RelTypeForm sent = captor.getValue().getItems().iterator().next();
        assertEquals("FLOWS_TO", sent.getName());
    }

    @Test
    void createTypes_duplicateName_returns409() throws Exception {
        // A type whose (case-insensitive) name already exists collides on the relationship_hash_key
        // unique constraint. That must surface as a 409, not the bare 500 it used to fall through to.
        when(edgeService.createRelationshipTypes(any()))
                .thenThrow(new DataIntegrityViolationException("relationship_hash_key"));

        mvc.perform(post("/edges/types/create")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"name\":\"FLOWS_TO\"}]}"))
                .andExpect(status().isConflict());
    }

    @Test
    void create_validRelation_returns201_andForwardsTheForm() throws Exception {
        EdgeProxy created = new EdgeProxy(341L, 5677892L, 5677893L, "FLOWS_TO", 7L, Map.of());
        when(edgeService.createRelationships(any()))
                .thenReturn(new DataWrapper<EdgeProxy>().setItems(List.of(created)));

        mvc.perform(post("/edges/create")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content("""
                                {"items":[{"fromExternalId":"pipe_a","toExternalId":"valve_v9",
                                           "relationshipType":"FLOWS_TO"}]}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.items[0].id").value("341"))
                .andExpect(jsonPath("$.items[0].type").value("FLOWS_TO"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<DataWrapper<RelForm>> captor = ArgumentCaptor.forClass(DataWrapper.class);
        verify(edgeService).createRelationships(captor.capture());
        RelForm sent = captor.getValue().getItems().iterator().next();
        assertEquals("pipe_a", sent.getFromExternalId());
        assertEquals("valve_v9", sent.getToExternalId());
        assertEquals("FLOWS_TO", sent.getRelationshipType());
    }

    @Test
    void create_noRelationshipType_returns400_andNeverReachesService() throws Exception {
        // @RelationshipTypeNotNull on RelForm: a relation must name a type or give its id.
        mvc.perform(post("/edges/create")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"fromId\":\"1\",\"toId\":\"2\"}]}"))
                .andExpect(status().isBadRequest());

        verify(edgeService, never()).createRelationships(any());
    }

    @Test
    void create_unknownEndpoint_returns400_withTheOffendingIdentifier() throws Exception {
        ResponseError<BadRequestError> error = new ResponseError<>();
        error.setError(new BadRequestError()
                .setMessage("Could not find toNode")
                .addFieldError("externalId", "valve_v9"));
        when(edgeService.createRelationships(any())).thenThrow(new BadRequestException(error));

        mvc.perform(post("/edges/create")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content("""
                                {"items":[{"fromExternalId":"pipe_a","toExternalId":"valve_v9",
                                           "relationshipType":"FLOWS_TO"}]}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.message").value("Could not find toNode"))
                .andExpect(jsonPath("$.error.fields[0].externalId").value("valve_v9"));
    }

    @Test
    void create_duplicateEdge_returns409() throws Exception {
        when(edgeService.createRelationships(any()))
                .thenThrow(new DataIntegrityViolationException("edge_rel_start_rel_end_relationship_type_id_key"));

        mvc.perform(post("/edges/create")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content("""
                                {"items":[{"fromId":"1","toId":"2","relationshipType":"FLOWS_TO"}]}"""))
                .andExpect(status().isConflict());
    }

    @Test
    void delete_wouldStrandNodes_returns400_withTheOffendingResources() throws Exception {
        // Removing an edge can disconnect a surviving node from the graph root. The shared
        // resource-delete pipeline refuses that and throws ResourceDeleteException carrying the
        // stranded resources. The controller must surface it as a 400 with that list — not swallow
        // it into the broad "error"/500 catch, which would hide from the client exactly which
        // resources block the delete.
        ResponseError<BadRequestError> error = new ResponseError<>();
        error.setError(new BadRequestError()
                .setMessage("Deleting this selection would disconnect resource(s) [klp_valve_v9]"
                        + " from the graph root. Include them in the deletion or keep a connecting path.")
                .addFieldError("externalId", "klp_valve_v9"));
        doThrow(new ResourceDeleteException(error)).when(edgeService).deleteRelationships(any());

        mvc.perform(post("/edges/delete")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"id\":341}]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.message")
                        .value(org.hamcrest.Matchers.containsString("klp_valve_v9")))
                .andExpect(jsonPath("$.error.fields[0].externalId").value("klp_valve_v9"));
    }

    @Test
    void delete_accessDenied_isNotMaskedAsA500() throws Exception {
        doThrow(new AccessDeniedException("no write access to data set 12"))
                .when(edgeService).deleteRelationships(any());

        // As on create: the denial must propagate so Spring Security produces a 403, rather than
        // being masked as a 500 by the broad Exception catch. MockMvc may wrap it in a
        // ServletException, so accept either shape.
        Exception thrown = assertThrows(Exception.class, () ->
                mvc.perform(post("/edges/delete")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"id\":341}]}")));
        assertTrue(thrown instanceof AccessDeniedException
                        || thrown.getCause() instanceof AccessDeniedException,
                "expected the AccessDeniedException to propagate, got " + thrown);
    }

    @Test
    void create_accessDenied_isNotMaskedAsA500() throws Exception {
        when(edgeService.createRelationships(any()))
                .thenThrow(new AccessDeniedException("no write access to data set 12"));

        // The denial must propagate out of the controller so Spring Security's entry point turns
        // it into a 403 — the broad RuntimeException catch would otherwise report a 500. MockMvc
        // may hand it back wrapped in a ServletException, so accept either shape.
        Exception thrown = assertThrows(Exception.class, () ->
                mvc.perform(post("/edges/create")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content("""
                                {"items":[{"fromId":"1","toId":"2","relationshipType":"FLOWS_TO"}]}""")));
        assertTrue(thrown instanceof AccessDeniedException
                        || thrown.getCause() instanceof AccessDeniedException,
                "expected the AccessDeniedException to propagate, got " + thrown);
    }
}
