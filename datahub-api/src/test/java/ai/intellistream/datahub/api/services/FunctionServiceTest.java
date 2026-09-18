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
import static org.mockito.ArgumentMatchers.anyInt;
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
    @Mock private ai.intellistream.datahub.api.datasecurity.DatasetClosureService datasetClosureService;

    private FunctionService functionService;

    @BeforeEach
    void setUp() {
        functionService = new FunctionService(functionRepository, resourceService, dataSecurity,
                datasetClosureService);
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
    void list_narrowsToReadableDatasets_andHidesOrphansFromANarrowedCaller() {
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

        when(functionRepository.findAll(any(org.springframework.data.domain.Sort.class)))
                .thenReturn(java.util.List.of(readable, hidden, orphan));
        when(dataSecurity.hasReadAccessToEverything()).thenReturn(false);
        when(dataSecurity.hasReadPermissionToDataSet(readable)).thenReturn(true);
        when(dataSecurity.hasReadPermissionToDataSet(hidden)).thenReturn(false);
        // An orphan cannot be matched against a data-set ACL, so only an all-datasets reader sees
        // one. This used to be listed to everybody while get() on the same id answered 404.
        when(dataSecurity.hasReadPermissionToDataSet(orphan)).thenReturn(false);

        var externalIds = functionService.list(1000).getItems().stream()
                .map(Function::getExternalId)
                .toList();

        // create/update/delete inherit the dataset ACL from ResourceService, but list() queries the
        // repository directly and returned every function on the tenant.
        assertEquals(java.util.List.of("fn_readable"), externalIds);
    }

    /**
     * The cap is applied after the dataset ACL, not in the query. Truncating first would let a
     * caller with narrow grants see fewer functions than they are entitled to while more readable
     * ones sat past the cut — the order is by creation, not by grant.
     */
    @Test
    void list_appliesTheLimitAfterNarrowingSoTheCapCountsReadableFunctions() {
        var readableDs = new ai.intellistream.datahub.jpa.domains.DatasetEntity();
        readableDs.setId(5L);
        var hiddenDs = new ai.intellistream.datahub.jpa.domains.DatasetEntity();
        hiddenDs.setId(9L);

        var hidden = new ai.intellistream.datahub.jpa.domains.FunctionEntity();
        hidden.setExternalId("fn_hidden");
        hidden.setDataSet(hiddenDs);
        var first = new ai.intellistream.datahub.jpa.domains.FunctionEntity();
        first.setExternalId("fn_first");
        first.setDataSet(readableDs);
        var second = new ai.intellistream.datahub.jpa.domains.FunctionEntity();
        second.setExternalId("fn_second");
        second.setDataSet(readableDs);

        // The unreadable one sorts first, so a cap applied in the query would spend the caller's
        // single slot on a row they may not see and return nothing.
        when(functionRepository.findAll(any(org.springframework.data.domain.Sort.class)))
                .thenReturn(java.util.List.of(hidden, first, second));
        when(dataSecurity.hasReadAccessToEverything()).thenReturn(false);
        when(dataSecurity.hasReadPermissionToDataSet(hidden)).thenReturn(false);
        when(dataSecurity.hasReadPermissionToDataSet(first)).thenReturn(true);

        var externalIds = functionService.list(1).getItems().stream()
                .map(Function::getExternalId)
                .toList();

        assertEquals(java.util.List.of("fn_first"), externalIds);
    }

    // ---- Collection reads --------------------------------------------------------------------

    @Test
    void byIds_narrowsToReadableDatasets_andHidesOrphansFromANarrowedCaller() {
        var ds = new ai.intellistream.datahub.jpa.domains.DatasetEntity();
        ds.setId(5L);
        var readable = new FunctionEntity();
        readable.setExternalId("fn_readable");
        readable.setDataSet(ds);

        var otherDs = new ai.intellistream.datahub.jpa.domains.DatasetEntity();
        otherDs.setId(9L);
        var hidden = new FunctionEntity();
        hidden.setExternalId("fn_hidden");
        hidden.setDataSet(otherDs);

        var orphan = new FunctionEntity();
        orphan.setExternalId("fn_orphan");

        when(functionRepository.findAllByIdOrExternalId(java.util.Set.of(1L), java.util.Set.<String>of()))
                .thenReturn(List.of(readable, hidden, orphan));
        when(dataSecurity.hasReadAccessToEverything()).thenReturn(false);
        when(dataSecurity.hasReadPermissionToDataSet(readable)).thenReturn(true);
        when(dataSecurity.hasReadPermissionToDataSet(hidden)).thenReturn(false);
        // Orphans are all-datasets-only, the same answer get() and the SQL data set scope give.
        when(dataSecurity.hasReadPermissionToDataSet(orphan)).thenReturn(false);

        var externalIds = functionService.byIds(java.util.Set.of(1L), java.util.Set.of()).getItems()
                .stream().map(Function::getExternalId).toList();

        assertEquals(List.of("fn_readable"), externalIds);
    }

    /**
     * The scope is null, not the readable set, for a caller who may read everything — the repository
     * reads null as "no restriction" and an empty collection as "return nothing", so handing it the
     * wrong one is the difference between every function and none.
     */
    @Test
    void filter_passesNoDataSetScope_forAReadAllCaller() {
        when(dataSecurity.hasReadAccessToEverything()).thenReturn(true);
        when(functionRepository.filter(anyInt(), any(), any(), any(), any())).thenReturn(List.of());

        functionService.filter(new ai.intellistream.datahub.models.FunctionRetreiver());

        verify(functionRepository).filter(anyInt(), org.mockito.ArgumentMatchers.isNull(),
                any(), any(), any());
    }

    @Test
    void filter_narrowsToTheCallersReadableDataSets() {
        when(dataSecurity.hasReadAccessToEverything()).thenReturn(false);
        when(dataSecurity.readableDataSetIds()).thenReturn(java.util.Set.of(5L, 7L));
        when(functionRepository.filter(anyInt(), any(), any(), any(), any())).thenReturn(List.of());

        functionService.filter(new ai.intellistream.datahub.models.FunctionRetreiver());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<java.util.Collection<Long>> scope = ArgumentCaptor.forClass(java.util.Collection.class);
        verify(functionRepository).filter(anyInt(), scope.capture(), any(), any(), any());
        assertEquals(java.util.Set.of(5L, 7L), new java.util.HashSet<>(scope.getValue()));
    }

    /**
     * A data set stands in for everything beneath it, and the closure is then intersected with what
     * the caller may read — naming a data set you cannot read must not widen the query.
     */
    @Test
    void filter_intersectsTheRequestedDataSetClosureWithTheReadableSet() {
        var form = new ai.intellistream.datahub.models.FunctionRetreiver();
        var ref = new IdCollection();
        ref.setId(5L);
        form.getFilter().setDataSetId(List.of(ref));

        when(dataSecurity.hasReadAccessToEverything()).thenReturn(false);
        when(dataSecurity.readableDataSetIds()).thenReturn(java.util.Set.of(5L, 6L));
        // 5 expands to itself plus child 8, which the caller may not read.
        when(datasetClosureService.closureOfReferences(any())).thenReturn(java.util.Set.of(5L, 8L));
        when(functionRepository.filter(anyInt(), any(), any(), any(), any())).thenReturn(List.of());

        functionService.filter(form);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<java.util.Collection<Long>> scope = ArgumentCaptor.forClass(java.util.Collection.class);
        verify(functionRepository).filter(anyInt(), scope.capture(), any(), any(), any());
        assertEquals(java.util.Set.of(5L), new java.util.HashSet<>(scope.getValue()));
    }

    /** No readable data sets is an empty scope, which the repository answers without querying. */
    @Test
    void filter_passesAnEmptyScope_whenTheCallerCanReadNoDataSets() {
        when(dataSecurity.hasReadAccessToEverything()).thenReturn(false);
        when(dataSecurity.readableDataSetIds()).thenReturn(java.util.Set.of());
        when(functionRepository.filter(anyInt(), any(), any(), any(), any())).thenReturn(List.of());

        functionService.filter(new ai.intellistream.datahub.models.FunctionRetreiver());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<java.util.Collection<Long>> scope = ArgumentCaptor.forClass(java.util.Collection.class);
        verify(functionRepository).filter(anyInt(), scope.capture(), any(), any(), any());
        assertTrue(scope.getValue().isEmpty());
    }

    /**
     * A search body may omit the filter entirely. That must narrow nothing and widen nothing: the
     * FUNCTION discriminator lives in the query, not in the filter, so the endpoint still answers
     * with functions only.
     */
    @Test
    void search_acceptsAnAbsentFilter() {
        var form = new ai.intellistream.datahub.models.SearchBody<
                ai.intellistream.datahub.models.datafilters.FunctionFilter>();
        form.getSearch().setQuery("rolling");
        when(dataSecurity.hasReadAccessToEverything()).thenReturn(true);
        when(functionRepository.search(any(), anyInt(), any(), any())).thenReturn(List.of());

        functionService.search(form);

        verify(functionRepository).search(org.mockito.ArgumentMatchers.eq("rolling"), anyInt(),
                org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.isNull());
    }
}
