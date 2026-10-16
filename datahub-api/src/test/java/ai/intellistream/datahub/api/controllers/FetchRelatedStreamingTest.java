// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.controllers;

import ai.intellistream.datahub.api.controllers.errors.ObjectNotFoundExceptionHandler;
import ai.intellistream.datahub.api.services.ResourceService;
import ai.intellistream.datahub.asset.GraphReadScope;
import ai.intellistream.datahub.asset.NetworkSink;
import ai.intellistream.datahub.asset.ResourceNetwork;
import ai.intellistream.datahub.errors.ObjectNotFoundException;
import ai.intellistream.datahub.jpa.domains.Label;
import ai.intellistream.datahub.models.Asset;
import ai.intellistream.datahub.models.EdgeProxy;
import ai.intellistream.datahub.models.NodeModel;
import ai.intellistream.datahub.models.NodeModelSubtypes;
import ai.intellistream.datahub.models.RelatedNode;
import ai.intellistream.datahub.models.RelatedResourcesForm;
import ai.intellistream.datahub.models.RelationDirection;
import ai.intellistream.datahub.timeseries.Timeseries;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * {@code POST /resources/fetch-related} now sends its JSON while the network is read. The caller must
 * receive exactly what it got when the whole {@link ResourceNetwork} was converted at once, and an
 * error that can still be reported properly (not found) must be reported before anything is sent.
 */
class FetchRelatedStreamingTest {

    private final JsonMapper mapper = JsonMapper.builder().addModule(new NodeModelSubtypes()).build();
    private final ResourceService service = mock(ResourceService.class);
    private MockMvc mvc;

    private Asset site;
    private Timeseries level;
    private EdgeProxy edge;
    private Set<Label> labels;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new ResourceController(service, mock(Validator.class), mapper))
                .setControllerAdvice(new ObjectNotFoundExceptionHandler())
                .build();

        site = new Asset();
        site.setId(1L);
        site.setExternalId("site");
        site.setName("Site");
        site.setDataSetId(10L);
        site.setIsRoot(true);
        site.setLabels(List.of("ASSET", "SITE"));
        site.setMetadata(Map.of("owner", "ops"));
        RelatedNode out = new RelatedNode();
        out.setId(2L);
        out.setExternalId("level");
        out.setRelationshipType("HAS");
        out.setDirection(RelationDirection.OUTBOUND);
        out.setEdgeId(100L);
        site.setRelatedResources(new ArrayList<>(List.of(out)));

        level = new Timeseries();
        level.setId(2L);
        level.setExternalId("level");
        level.setName("Level");
        level.setUnit("m");
        level.setLabels(List.of("TIMESERIES"));

        edge = new EdgeProxy(100L, 1L, 2L, "HAS", 7L, Map.of("animate::datatype", "oil"));

        Label label = new Label();
        label.setId(5L);
        label.setName("SITE");
        label.setColor("#123456");
        labels = new HashSet<>(Set.of(label));
    }

    private void streams(NodeModel... nodes) {
        streamsCappedAt(0, nodes);
    }

    private void streamsCappedAt(long cappedAt, NodeModel... nodes) {
        var prepared = new ResourceService.PreparedNetwork(new RelatedResourcesForm(),
                GraphReadScope.readEverything(), labels, cappedAt);
        when(service.prepareRelatedResources(any())).thenReturn(prepared);
        doAnswer(inv -> {
            NetworkSink sink = inv.getArgument(1);
            sink.edge(edge);
            for (NodeModel node : nodes) {
                sink.node(node);
            }
            return null;
        }).when(service).streamRelatedResources(any(), any());
    }

    private MvcResult fetch(String accept) throws Exception {
        var request = post("/resources/fetch-related").contentType(MediaType.APPLICATION_JSON)
                .content("{\"id\":\"1\",\"depth\":5}");
        if (accept != null) {
            request = request.header("Accept", accept);
        }
        return mvc.perform(request).andReturn();
    }

    /** The lists are compared ignoring order: the response has never promised an order. */
    private static Map<String, Set<JsonNode>> bySection(JsonNode document) {
        Map<String, Set<JsonNode>> sections = new java.util.HashMap<>();
        for (String name : document.propertyNames()) {
            Set<JsonNode> items = new LinkedHashSet<>();
            document.get(name).forEach(items::add);
            sections.put(name, items);
        }
        return sections;
    }

    @Test
    void theStreamedJsonIsWhatTheWholeNetworkSerialisedTo() throws Exception {
        streams(site, level);

        MvcResult result = fetch(null);

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(result.getResponse().getContentType()).startsWith(MediaType.APPLICATION_JSON_VALUE);
        var whole = new ResourceNetwork(new HashSet<>(List.of(site, level)), new HashSet<>(List.of(edge)), labels);
        JsonNode expected = mapper.readTree(mapper.writeValueAsString(whole));
        JsonNode actual = mapper.readTree(result.getResponse().getContentAsByteArray());
        assertThat(bySection(actual)).isEqualTo(bySection(expected));
        assertThat(actual.get("nodes")).hasSize(2);
        verify(service, never()).fetchRelatedResources(any());
    }

    @Test
    void aNetworkWithNoNodesIsStillAWholeDocument() throws Exception {
        var prepared = new ResourceService.PreparedNetwork(new RelatedResourcesForm(),
                GraphReadScope.readEverything(), Set.of(), 0);
        when(service.prepareRelatedResources(any())).thenReturn(prepared);

        JsonNode actual = mapper.readTree(fetch("application/json").getResponse().getContentAsByteArray());

        assertThat(actual.get("edges")).isEmpty();
        assertThat(actual.get("nodes")).isEmpty();
        assertThat(actual.get("labels")).isEmpty();
    }

    @Test
    void anUnknownOrUnreadableStartIsStillA404() throws Exception {
        when(service.prepareRelatedResources(any()))
                .thenThrow(new ObjectNotFoundException("Resource with id: 1 not found."));

        MvcResult result = fetch(null);

        assertThat(result.getResponse().getStatus()).isEqualTo(404);
        verify(service, never()).streamRelatedResources(any(), any());
    }

    @Test
    void xmlStillComesFromTheWholeNetwork() throws Exception {
        when(service.prepareRelatedResources(any())).thenReturn(new ResourceService.PreparedNetwork(
                new RelatedResourcesForm(), GraphReadScope.readEverything(), labels, 5_000));
        when(service.readRelatedResources(any())).thenReturn(
                new ResourceNetwork(new HashSet<>(List.of(site)), new HashSet<>(), new HashSet<>()));

        MvcResult result = fetch("application/xml");

        verify(service).readRelatedResources(any());
        verify(service, never()).streamRelatedResources(any(), any());
        assertThat(result.getResponse().getHeader("X-Network-Cap")).isEqualTo("5000");
    }

    /** A network cut short looks the same as a small one, so a response the playground limit cut short says so. */
    @Test
    void aCappedReadSaysWhichCapCutIt() throws Exception {
        streamsCappedAt(5_000, site);

        assertThat(fetch(null).getResponse().getHeader("X-Network-Cap")).isEqualTo("5000");
    }

    @Test
    void anUncappedReadCarriesNoCap() throws Exception {
        streams(site);

        assertThat(fetch(null).getResponse().getHeader("X-Network-Cap")).isNull();
    }

    @Test
    void aWildcardOrAJsonPreferenceStreams() throws Exception {
        streams(site);

        fetch("*/*");
        fetch("application/xml;q=0.5, application/json");

        verify(service, never()).fetchRelatedResources(any());
    }
}
