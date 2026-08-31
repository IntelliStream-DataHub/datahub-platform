// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.datasecurity;

import ai.intellistream.datahub.jpa.domains.NodeType;
import ai.intellistream.datahub.models.IdCollection;
import ai.intellistream.datahub.repositories.node.DataSetRepository;
import ai.intellistream.datahub.services.ValkeyService;
import ai.intellistream.datahub.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Caching and invalidation for {@link DatasetClosureService}. The closure query itself is covered
 * against a real PostgreSQL by {@code DatasetClosureIT} in datahub-infra; this covers the tier in
 * front of it.
 */
class DatasetClosureServiceTest {

    private static final String TENANT = "tenant-acme";
    private static final String GEN_KEY = "acl:gen:" + TENANT;

    private final DataSetRepository dataSetRepository = mock(DataSetRepository.class);
    private final ValkeyService valkeyService = mock(ValkeyService.class);
    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    private final Map<String, String> store = new HashMap<>();
    private final AtomicLong generation = new AtomicLong(0);

    private DatasetClosureService service;

    @BeforeEach
    void setUp() {
        TenantContext.setTenantId(TENANT);

        when(valkeyService.multiGet(any())).thenAnswer(inv -> {
            List<String> keys = inv.getArgument(0);
            Map<String, String> out = new HashMap<>();
            for (String k : keys) {
                String v = GEN_KEY.equals(k) ? genValue() : store.get(k);
                if (v != null) out.put(k, v);
            }
            return out;
        });
        doAnswer(inv -> {
            store.put(inv.getArgument(0, String.class), inv.getArgument(1, String.class));
            return null;
        }).when(valkeyService).setString(anyString(), anyString(), anyLong());
        when(valkeyService.increment(eq(GEN_KEY), anyLong()))
                .thenAnswer(inv -> generation.addAndGet(inv.getArgument(1, Long.class)));

        service = new DatasetClosureService(dataSetRepository, valkeyService, jsonMapper,
                Duration.ofMinutes(30));
    }

    private String genValue() {
        long g = generation.get();
        return g == 0 ? null : Long.toString(g);
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    /** Roots resolve to ids, then the closure expands them. */
    private void graphIs(Map<String, Long> rootsByExternalId, List<Long> closure) {
        List<Long> rootIds = new ArrayList<>(rootsByExternalId.values());
        when(dataSetRepository.findDatasetIdsByExternalIdHashIn(any(), eq(NodeType.DATASET)))
                .thenReturn(rootIds);
        when(dataSetRepository.findDatasetClosure(any(), eq(NodeType.DATASET), eq("BELONGS_TO")))
                .thenReturn(closure);
    }

    @Test
    void expandsGrantsThroughTheHierarchy() {
        graphIs(Map.of("data_set_sap", 10L), List.of(10L, 11L, 12L));

        assertThat(service.closureOfExternalIds(List.of("data_set_sap"))).containsExactlyInAnyOrder(10L, 11L, 12L);
    }

    @Test
    void returnsEmptyForNoGrants() {
        assertThat(service.closureOfExternalIds(List.of())).isEmpty();
        assertThat(service.closureOfExternalIds(null)).isEmpty();
        verify(dataSetRepository, never()).findDatasetClosure(any(), anyLong(), anyString());
    }

    /** A group naming a dataset that does not exist grants nothing rather than erroring. */
    @Test
    void unresolvableExternalIdsGrantNothing() {
        when(dataSetRepository.findDatasetIdsByExternalIdHashIn(any(), eq(NodeType.DATASET)))
                .thenReturn(List.of());

        assertThat(service.closureOfExternalIds(List.of("data_set_typo"))).isEmpty();
        verify(dataSetRepository, never()).findDatasetClosure(any(), anyLong(), anyString());
    }

    // ---- caching ---------------------------------------------------------------------------

    @Test
    void secondCallIsServedFromCache() {
        graphIs(Map.of("data_set_sap", 10L), List.of(10L, 11L));

        service.closureOfExternalIds(List.of("data_set_sap"));
        Set<Long> second = service.closureOfExternalIds(List.of("data_set_sap"));

        assertThat(second).containsExactlyInAnyOrder(10L, 11L);
        verify(dataSetRepository, times(1)).findDatasetClosure(any(), anyLong(), anyString());
    }

    /** Grant order must not produce a second cache entry. */
    @Test
    void grantOrderDoesNotAffectTheCacheKey() {
        graphIs(Map.of("data_set_a", 1L), List.of(1L, 2L));

        service.closureOfExternalIds(List.of("data_set_a", "data_set_b"));
        service.closureOfExternalIds(List.of("data_set_b", "data_set_a"));

        verify(dataSetRepository, times(1)).findDatasetClosure(any(), anyLong(), anyString());
    }

    @Test
    void differentGrantSetsGetDifferentEntries() {
        graphIs(Map.of("data_set_a", 1L), List.of(1L));

        service.closureOfExternalIds(List.of("data_set_a"));
        service.closureOfExternalIds(List.of("data_set_b"));

        verify(dataSetRepository, times(2)).findDatasetClosure(any(), anyLong(), anyString());
    }

    // ---- invalidation ----------------------------------------------------------------------

    /**
     * Bumping the generation must make every cached closure for the tenant recompute, which is what
     * makes a dataset re-parenting take effect without hunting down individual keys.
     */
    @Test
    void invalidationForcesARecompute() {
        graphIs(Map.of("data_set_sap", 10L), List.of(10L, 11L));
        service.closureOfExternalIds(List.of("data_set_sap"));

        service.invalidate();
        when(dataSetRepository.findDatasetClosure(any(), eq(NodeType.DATASET), eq("BELONGS_TO")))
                .thenReturn(List.of(10L, 11L, 12L));

        assertThat(service.closureOfExternalIds(List.of("data_set_sap")))
                .containsExactlyInAnyOrder(10L, 11L, 12L);
        verify(dataSetRepository, times(2)).findDatasetClosure(any(), anyLong(), anyString());
    }

    @Test
    void invalidationAffectsEveryGrantSetAtOnce() {
        graphIs(Map.of("data_set_a", 1L), List.of(1L));
        service.closureOfExternalIds(List.of("data_set_a"));
        service.closureOfExternalIds(List.of("data_set_b"));

        service.invalidate();
        service.closureOfExternalIds(List.of("data_set_a"));
        service.closureOfExternalIds(List.of("data_set_b"));

        // Two grant sets, computed once each before invalidation and once each after.
        verify(dataSetRepository, times(4)).findDatasetClosure(any(), anyLong(), anyString());
    }

    @Test
    void reCachesAtTheNewGenerationSoOnlyOneRecomputeHappens() {
        graphIs(Map.of("data_set_sap", 10L), List.of(10L));
        service.closureOfExternalIds(List.of("data_set_sap"));
        service.invalidate();

        service.closureOfExternalIds(List.of("data_set_sap"));
        service.closureOfExternalIds(List.of("data_set_sap"));

        verify(dataSetRepository, times(2)).findDatasetClosure(any(), anyLong(), anyString());
    }

    // ---- degradation -----------------------------------------------------------------------

    /** A Valkey outage must fall through to a live query, never deny. */
    @Test
    void survivesAValkeyFailure() {
        // doThrow, not when(...).thenThrow: the latter would invoke the existing answer with a
        // null argument list while stubbing.
        doThrow(new RuntimeException("valkey down")).when(valkeyService).multiGet(any());
        doThrow(new RuntimeException("valkey down"))
                .when(valkeyService).setString(anyString(), anyString(), anyLong());
        graphIs(Map.of("data_set_sap", 10L), List.of(10L, 11L));

        assertThat(service.closureOfExternalIds(List.of("data_set_sap"))).containsExactlyInAnyOrder(10L, 11L);
    }

    /** A failed generation bump is logged, not thrown: it must not fail the write that triggered it. */
    @Test
    void invalidationSwallowsAValkeyFailure() {
        doThrow(new RuntimeException("valkey down")).when(valkeyService).increment(anyString(), anyLong());

        service.invalidate();
    }

    @Test
    void requiresATenantInContext() {
        TenantContext.clear();

        assertThatThrownBy(() -> service.closureOfExternalIds(List.of("data_set_sap")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("TenantContext");
    }

    // ---- closureOf: the filter path -------------------------------------------------------
    // dataSetId=X on a query filter now resolves through the same closure a grant on X does. It
    // used to walk the Neo4j mirror, which is written after the transaction commits, so the two
    // could disagree about what is beneath a dataset.

    @Test
    void closureOfExpandsThroughTheSameHierarchyAsAGrant() {
        when(dataSetRepository.findDatasetClosure(eq(Set.of(10L)), eq(NodeType.DATASET), eq("BELONGS_TO")))
                .thenReturn(List.of(10L, 11L, 12L));

        assertThat(service.closureOf(java.util.Set.of(10L))).containsExactlyInAnyOrder(10L, 11L, 12L);
    }

    @Test
    void closureOfIsCachedPerDataSetSet() {
        when(dataSetRepository.findDatasetClosure(eq(Set.of(10L)), eq(NodeType.DATASET), eq("BELONGS_TO")))
                .thenReturn(List.of(10L, 11L));

        service.closureOf(java.util.Set.of(10L));
        service.closureOf(java.util.Set.of(10L));

        // The recursive query runs once; the filter path had no cache at all before.
        verify(dataSetRepository, times(1))
                .findDatasetClosure(eq(Set.of(10L)), eq(NodeType.DATASET), eq("BELONGS_TO"));
    }

    @Test
    void closureOfIsInvalidatedByTheSameGenerationBumpAsGrants() {
        when(dataSetRepository.findDatasetClosure(eq(Set.of(10L)), eq(NodeType.DATASET), eq("BELONGS_TO")))
                .thenReturn(List.of(10L, 11L));
        assertThat(service.closureOf(java.util.Set.of(10L))).containsExactlyInAnyOrder(10L, 11L);

        // A new child dataset lands: one INCR has to invalidate both key spaces, or a filter would
        // keep missing rows the ACL already lets the caller read.
        when(dataSetRepository.findDatasetClosure(eq(Set.of(10L)), eq(NodeType.DATASET), eq("BELONGS_TO")))
                .thenReturn(List.of(10L, 11L, 12L));
        service.invalidate();

        assertThat(service.closureOf(java.util.Set.of(10L))).containsExactlyInAnyOrder(10L, 11L, 12L);
    }

    @Test
    void aDatasetIdClosureNeverCollidesWithAGrantClosure() {
        // Distinct key spaces: a dataset id and a grant fingerprint must not share a cache entry.
        when(dataSetRepository.findDatasetIdsByExternalIdHashIn(any(), eq(NodeType.DATASET)))
                .thenReturn(List.of(99L));
        // closureOfExternalIds() reaches the closure with the List its id lookup returned; closureOf() with
        // the sorted set of roots. Different collection types, so the stubs must match each.
        when(dataSetRepository.findDatasetClosure(eq(List.of(99L)), eq(NodeType.DATASET), eq("BELONGS_TO")))
                .thenReturn(List.of(99L));
        when(dataSetRepository.findDatasetClosure(eq(Set.of(10L)), eq(NodeType.DATASET), eq("BELONGS_TO")))
                .thenReturn(List.of(10L, 11L));

        assertThat(service.closureOfExternalIds(List.of("some_grant"))).containsExactly(99L);
        assertThat(service.closureOf(java.util.Set.of(10L))).containsExactlyInAnyOrder(10L, 11L);
    }

    // ---- closureOfReferences: id-or-externalId resolution ------------------------------------
    // This resolution used to be written out once per service (events, resources, timeseries) and
    // had drifted between them. These cases moved here with it, from EventServiceTest.

    @Test
    void closureOfReferences_passesIdReferencesStraightThrough() {
        when(dataSetRepository.findDatasetClosure(any(), eq(NodeType.DATASET), anyString()))
                .thenReturn(List.of(12L, 34L));

        Set<Long> result = service.closureOfReferences(List.of(
                IdCollection.createFromId(12L), IdCollection.createFromId(34L)));

        assertThat(result).containsExactlyInAnyOrder(12L, 34L);
        verify(dataSetRepository, never()).findDatasetIdsByExternalIdHashIn(any(), anyLong());
    }

    @Test
    void closureOfReferences_resolvesExternalIdReferencesToIds() {
        when(dataSetRepository.findDatasetIdsByExternalIdHashIn(any(), eq(NodeType.DATASET)))
                .thenReturn(List.of(34L));
        when(dataSetRepository.findDatasetClosure(any(), eq(NodeType.DATASET), anyString()))
                .thenReturn(List.of(34L));

        Set<Long> result = service.closureOfReferences(
                List.of(IdCollection.createFromExternalId("data_set_sap")));

        assertThat(result).containsExactly(34L);
    }

    /** Ids and externalIds in one list resolve to the union, in a single lookup. */
    @Test
    void closureOfReferences_unionsIdsAndExternalIdsInOneLookup() {
        when(dataSetRepository.findDatasetIdsByExternalIdHashIn(any(), eq(NodeType.DATASET)))
                .thenReturn(List.of(34L, 12L));
        when(dataSetRepository.findDatasetClosure(any(), eq(NodeType.DATASET), anyString()))
                .thenReturn(List.of(12L, 34L));

        Set<Long> result = service.closureOfReferences(List.of(
                IdCollection.createFromId(12L),
                IdCollection.createFromExternalId("data_set_sap"),
                IdCollection.createFromExternalId("plant_a")));

        assertThat(result).containsExactlyInAnyOrder(12L, 34L);
        verify(dataSetRepository, times(1)).findDatasetIdsByExternalIdHashIn(any(), anyLong());
    }

    /**
     * An externalId naming no data set yields an EMPTY closure, not a null one — the caller asked
     * to be narrowed to those data sets, so the query must match nothing rather than everything.
     */
    @Test
    void closureOfReferences_withOnlyUnknownExternalIds_narrowsToNothing() {
        when(dataSetRepository.findDatasetIdsByExternalIdHashIn(any(), eq(NodeType.DATASET)))
                .thenReturn(List.of());

        Set<Long> result = service.closureOfReferences(
                List.of(IdCollection.createFromExternalId("data_set_typo")));

        assertThat(result).isEmpty();
        verify(dataSetRepository, never()).findDatasetClosure(any(), anyLong(), anyString());
    }

    /** Each named data set expands to everything beneath it, the same closure a grant covers. */
    @Test
    void closureOfReferences_expandsEachDataSetToItsHierarchy() {
        when(dataSetRepository.findDatasetClosure(any(), eq(NodeType.DATASET), anyString()))
                .thenReturn(List.of(12L, 13L, 14L));

        Set<Long> result = service.closureOfReferences(List.of(IdCollection.createFromId(12L)));

        assertThat(result).containsExactlyInAnyOrder(12L, 13L, 14L);
    }

    @Test
    void closureOfReferences_withNoReferences_isEmpty() {
        assertThat(service.closureOfReferences(null)).isEmpty();
        assertThat(service.closureOfReferences(List.of())).isEmpty();
    }
}
