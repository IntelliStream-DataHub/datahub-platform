// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.services;

import ai.intellistream.datahub.models.NodeModel;
import ai.intellistream.datahub.api.responses.DataWrapper;
import ai.intellistream.datahub.api.responses.GraphDataWrapper;
import ai.intellistream.datahub.function.Function;
import ai.intellistream.datahub.jpa.domains.FunctionEntity;
import ai.intellistream.datahub.models.EdgeProxy;
import ai.intellistream.datahub.models.IdCollection;
import ai.intellistream.datahub.models.RelForm;
import ai.intellistream.datahub.models.Resource;
import ai.intellistream.datahub.models.UpdateRelForm;
import ai.intellistream.datahub.models.UpdateResourceForm;
import ai.intellistream.datahub.repositories.node.FunctionRepository;
import ai.intellistream.datahub.transformers.FunctionTransformer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import ai.intellistream.datahub.api.datasecurity.DataSecurity;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A Function is a plain datastore node and {@link FunctionService} is a thin adapter over the
 * shared {@link ResourceService} pipeline. These tests verify the delegation: functions carry
 * the canonical {@code FUNCTION} label into the resource create/update/delete flow, and the
 * response is re-read as {@link Function} DTOs.
 */
@ExtendWith(MockitoExtension.class)
class FunctionServiceTest {

    @Mock private FunctionRepository functionRepository;
    @Mock private ResourceService resourceService;
    @Mock private DataSecurity dataSecurity;

    private FunctionService functionService;

    @BeforeEach
    void setUp() {
        functionService = new FunctionService(functionRepository, new FunctionTransformer(), resourceService, dataSecurity);
    }

    @Test
    void create_delegatesToResourcePipeline_withCanonicalFunctionLabel() throws Exception {
        var fn = new Function();
        fn.setExternalId("my_fn");
        fn.setName("My Function");

        // resourceService.create returns one created node with a server-assigned id.
        var created = new GraphDataWrapper<NodeModel, EdgeProxy>();
        var node = new Resource();
        node.setId(1L);
        created.getNodes().add(node);
        when(resourceService.create(any())).thenReturn(created);

        // The re-read that shapes the response back into Function DTOs.
        var entity = new FunctionEntity();
        entity.setExternalId("my_fn");
        entity.setName("My Function");
        when(functionRepository.findAllById(List.of(1L))).thenReturn(List.of(entity));

        DataWrapper<Function> result = functionService.create(wrap(fn));

        assertEquals(1, result.getItems().size());
        assertEquals("my_fn", result.getItems().iterator().next().getExternalId());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<GraphDataWrapper<NodeModel, RelForm>> captor =
                ArgumentCaptor.forClass(GraphDataWrapper.class);
        verify(resourceService).create(captor.capture());
        NodeModel passed = captor.getValue().getNodes().iterator().next();
        assertTrue(passed.getLabels().contains("FUNCTION"),
                "the FUNCTION type-label must reach the resource pipeline so a FunctionEntity is built");
    }

    @Test
    void update_delegatesToResourcePipeline() throws Exception {
        var req = new GraphDataWrapper<UpdateResourceForm, UpdateRelForm>();
        var expected = new GraphDataWrapper<NodeModel, EdgeProxy>();
        when(resourceService.update(req)).thenReturn(expected);

        assertSame(expected, functionService.update(req));
        verify(resourceService).update(req);
    }

    @Test
    void delete_resolvesReferences_andDelegatesToResourcePipeline() throws Exception {
        var req = new DataWrapper<IdCollection>();
        var ref = new IdCollection();
        ref.setExternalId("fn_a");
        req.getItems().add(ref);

        functionService.delete(req);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<GraphDataWrapper<Resource, EdgeProxy>> captor =
                ArgumentCaptor.forClass(GraphDataWrapper.class);
        verify(resourceService).delete(captor.capture());
        assertEquals("fn_a", captor.getValue().getNodes().iterator().next().getExternalId());
    }

    @Test
    void delete_empty_isNoop() throws Exception {
        functionService.delete(new DataWrapper<>());
        verify(resourceService, never()).delete(any());
    }

    private static DataWrapper<Function> wrap(Function fn) {
        var w = new DataWrapper<Function>();
        w.getItems().add(fn);
        return w;
    }

    @Test
    void list_narrowsToReadableDatasets_butKeepsDatasetlessFunctions() {
        var ds = new ai.intellistream.datahub.jpa.domains.DatasetEntity();
        ds.setId(5L);
        var readable = new ai.intellistream.datahub.jpa.domains.FunctionEntity();
        readable.setExternalId("fn_readable");
        readable.setDataSet(ds);

        var otherDs = new ai.intellistream.datahub.jpa.domains.DatasetEntity();
        otherDs.setId(9L);
        var hidden = new ai.intellistream.datahub.jpa.domains.FunctionEntity();
        hidden.setExternalId("fn_hidden");
        hidden.setDataSet(otherDs);

        var orphan = new ai.intellistream.datahub.jpa.domains.FunctionEntity();
        orphan.setExternalId("fn_orphan");

        when(functionRepository.findAll()).thenReturn(java.util.List.of(readable, hidden, orphan));
        when(dataSecurity.hasReadAccessToEverything()).thenReturn(false);
        when(dataSecurity.readableDataSetIds()).thenReturn(java.util.Set.of(5L));

        var externalIds = functionService.list().getItems().stream()
                .map(Function::getExternalId)
                .toList();

        // create/update/delete inherit the dataset ACL from ResourceService, but list() queries the
        // repository directly and returned every function on the tenant.
        assertEquals(java.util.List.of("fn_readable", "fn_orphan"), externalIds);
    }
}
