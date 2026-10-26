// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.flow.flows;

import ai.intellistream.datahub.api.responses.DataWrapper;
import ai.intellistream.datahub.flow.access.Caller;
import ai.intellistream.datahub.flow.access.Caller.DataSets;
import ai.intellistream.datahub.flow.access.CallerAccess;
import ai.intellistream.datahub.flow.definition.FlowValidator;
import ai.intellistream.datahub.flow.flows.FlowRepository.StoredFlow;
import ai.intellistream.datahub.flow.flows.FlowRepository.StoredVersion;
import ai.intellistream.datahub.flow.identity.FlowTokens;
import ai.intellistream.datahub.flow.store.RunStore;
import ai.intellistream.datahub.flow.web.FlowProblems;
import ai.intellistream.datahub.models.DataSetModel;
import ai.intellistream.datahub.sdk.client.DatahubClient;
import ai.intellistream.datahub.sdk.client.DatasetService;
import ai.intellistream.datahub.sdk.client.TimeseriesService;
import ai.intellistream.datahub.tenant.Tenant;
import ai.intellistream.datahub.tenant.TenantConfigService;
import ai.intellistream.datahub.tenant.TenantConfigWriter;
import ai.intellistream.datahub.timeseries.Timeseries;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The flows API's access rules and save path, through the real {@link FlowService}, with
 * datahub-api and the database faked: who may see and change which flows, what a save creates, and
 * how each refusal answers.
 */
class FlowControllerTest {

    private static final long DS_DEMO = 11;
    private static final long DS_OTHER = 22;
    private static final long DS_GONE = 33;

    private final FlowRepository repository = mock(FlowRepository.class);
    private final CallerAccess access = mock(CallerAccess.class);
    private final DatahubClient api = mock(DatahubClient.class);
    private final DatasetService datasets = mock(DatasetService.class);
    private final TimeseriesService timeseries = mock(TimeseriesService.class);
    private final FlowTokens offlineTokens = mock(FlowTokens.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        TenantConfigService tenants = mock(TenantConfigService.class);
        Tenant tenant = new Tenant();
        tenant.setOrganizationName("foo");
        when(tenants.getConfig(any())).thenReturn(tenant);
        TenantConfigWriter tenantConfig = mock(TenantConfigWriter.class);
        when(tenantConfig.current("foo")).thenReturn(Map.of());

        when(access.client()).thenReturn(api);
        when(api.datasets()).thenReturn(datasets);
        when(api.timeseries()).thenReturn(timeseries);
        when(datasets.byIds(any())).thenReturn(wrap(List.of(dataSet(DS_DEMO, "ds_demo"),
                dataSet(DS_OTHER, "ds_other"))));
        when(timeseries.byIds(any())).thenReturn(wrap(List.of(series("ts_in_temp_f"))));

        FlowService service = new FlowService(repository, access,
                new FlowValidator(TestFlows.builtIns(), FlowValidator.Limits.DEFAULTS), tenants,
                tenantConfig, new TransactionTemplate(mock(PlatformTransactionManager.class)),
                JsonMapper.builder().build(), offlineTokens, mock(RunStore.class));
        mvc = MockMvcBuilders.standaloneSetup(new FlowController(service))
                .setControllerAdvice(new FlowProblems())
                .build();
    }

    @Test
    void creatingTakesTheWriteGrant() throws Exception {
        callerIs(reader(DataSets.ALL));

        create(TestFlows.EXAMPLE).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value("https://intellistream.ai/errors/forbidden"));
        verify(repository, never()).create(anyString(), anyLong(), anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void creatingTakesWriteAccessToTheFlowsDataset() throws Exception {
        callerIs(writer(new DataSets(false, Set.of(DS_OTHER))));

        create(TestFlows.EXAMPLE).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value("https://intellistream.ai/errors/dataset-forbidden"));
    }

    @Test
    void aSaveCreatesTheDeclaredSeriesThatDoNotExistYet() throws Exception {
        callerIs(writer(new DataSets(false, Set.of(DS_DEMO))));
        when(repository.find("temp_hourly")).thenReturn(Optional.empty());
        when(repository.create(eq("temp_hourly"), eq(DS_DEMO), eq("ds_demo"), anyString(), eq("flow"), anyString(),
                eq("user-1"))).thenReturn(stored("temp_hourly", DS_DEMO, 1));

        create(TestFlows.EXAMPLE).andExpect(status().isCreated())
                .andExpect(jsonPath("$.externalId").value("temp_hourly"))
                .andExpect(jsonPath("$.dataSetId").value(String.valueOf(DS_DEMO)))
                .andExpect(jsonPath("$.createdSeries[0]").value("ts_out_temp_f"))
                .andExpect(jsonPath("$.createdSeries.length()").value(1))
                .andExpect(jsonPath("$.definition.processors[0].id").value("src"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Timeseries>> created = ArgumentCaptor.forClass(List.class);
        verify(timeseries).create(created.capture());
        Timeseries made = created.getValue().getFirst();
        assertThat(made.getExternalId()).isEqualTo("ts_out_temp_f");
        assertThat(made.getName()).isEqualTo("ts_out_temp in °F");
        assertThat(made.getUnit()).isEqualTo("°F");
        assertThat(made.getDataSetId()).isEqualTo(DS_DEMO);
    }

    @Test
    void anInvalidDefinitionListsEveryProblemAtItsPointer() throws Exception {
        callerIs(writer(DataSets.ALL));

        create(TestFlows.EXAMPLE.replace("\"factor\": 1.8", "\"factor\": \"many\"")
                .replace("\"name\": \"Hourly temperature\",", "\"name\": \"x\", \"retries\": 3,"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://intellistream.ai/errors/validation-failed"))
                .andExpect(jsonPath("$.fields[0].field").value("#/retries"));
        verify(timeseries, never()).create(any(List.class));
    }

    @Test
    void anExternalIdThatIsTakenIsAConflict() throws Exception {
        callerIs(writer(DataSets.ALL));
        when(repository.find("temp_hourly")).thenReturn(Optional.of(stored("temp_hourly", DS_DEMO, 1)));

        create(TestFlows.EXAMPLE).andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("https://intellistream.ai/errors/duplicate"));
    }

    @Test
    void listingTakesTheReadGrantAndIsFilteredByReadableDatasets() throws Exception {
        callerIs(new Caller("user-1", false, false, true, DataSets.ALL, DataSets.ALL));
        mvc.perform(get("/flows")).andExpect(status().isForbidden());

        DataSets readable = new DataSets(false, Set.of(DS_DEMO));
        callerIs(reader(readable));
        when(repository.list(readable, false, null, null, FlowService.DEFAULT_LIMIT))
                .thenReturn(List.of(stored("temp_hourly", DS_DEMO, 2)));
        mvc.perform(get("/flows")).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].externalId").value("temp_hourly"))
                .andExpect(jsonPath("$.items[0].version").value(2))
                .andExpect(jsonPath("$.items[0].kind").value("flow"))
                .andExpect(jsonPath("$.items[0].definition").doesNotExist())
                .andExpect(jsonPath("$.nextCursor").doesNotExist());
    }

    @Test
    void aListingCanBeOfOneKind() throws Exception {
        callerIs(reader(DataSets.ALL));
        when(repository.list(DataSets.ALL, false, "agent", null, FlowService.DEFAULT_LIMIT))
                .thenReturn(List.of(new StoredFlow(8, "pump_watch", DS_DEMO, "ds_demo", "Pump watch", 1, null,
                        false, "user-1", Instant.EPOCH, "user-1", Instant.EPOCH, null, "agent")));
        mvc.perform(get("/flows").param("kind", "agent")).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].externalId").value("pump_watch"))
                .andExpect(jsonPath("$.items[0].kind").value("agent"));
        mvc.perform(get("/flows").param("kind", "robot")).andExpect(status().isBadRequest());
        verify(repository, never()).list(any(), anyBoolean(), eq("robot"), any(), anyInt());
    }

    @Test
    void aFullPageCarriesACursorThatContinuesAfterItsLastFlow() throws Exception {
        callerIs(reader(DataSets.ALL));
        when(repository.list(DataSets.ALL, false, null, null, 1)).thenReturn(List.of(stored("a", DS_DEMO, 1)));
        String body = mvc.perform(get("/flows").param("limit", "1")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String cursor = JsonMapper.builder().build().readTree(body).get("nextCursor").asString();

        when(repository.list(DataSets.ALL, false, null, "a", 1)).thenReturn(List.of());
        mvc.perform(get("/flows").param("limit", "1").param("cursor", cursor)).andExpect(status().isOk());
        verify(repository).list(DataSets.ALL, false, null, "a", 1);

        mvc.perform(get("/flows").param("cursor", "not-a-cursor")).andExpect(status().isBadRequest());
        mvc.perform(get("/flows").param("limit", "1001")).andExpect(status().isBadRequest());
    }

    @Test
    void aFlowInADatasetTheCallerCannotReadDoesNotExistForThem() throws Exception {
        callerIs(reader(new DataSets(false, Set.of(DS_OTHER))));
        when(repository.find("temp_hourly")).thenReturn(Optional.of(stored("temp_hourly", DS_DEMO, 1)));

        mvc.perform(get("/flows/temp_hourly")).andExpect(status().isNotFound());
        mvc.perform(get("/flows/temp_hourly/versions")).andExpect(status().isNotFound());
    }

    @Test
    void aReaderSeesTheFlowWithItsCurrentDefinitionAndItsVersions() throws Exception {
        callerIs(reader(new DataSets(false, Set.of(DS_DEMO))));
        StoredFlow flow = stored("temp_hourly", DS_DEMO, 2);
        when(repository.find("temp_hourly")).thenReturn(Optional.of(flow));
        when(repository.version(flow.id(), 2)).thenReturn(Optional.of(
                new StoredVersion(2, TestFlows.EXAMPLE, "user-1", Instant.EPOCH)));
        when(repository.versions(flow.id())).thenReturn(List.of(
                new StoredVersion(2, null, "user-1", Instant.EPOCH),
                new StoredVersion(1, null, "user-1", Instant.EPOCH)));

        mvc.perform(get("/flows/temp_hourly")).andExpect(status().isOk())
                .andExpect(jsonPath("$.definition.externalId").value("temp_hourly"));
        mvc.perform(get("/flows/temp_hourly/versions")).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].version").value(2))
                .andExpect(jsonPath("$[1].version").value(1));
        mvc.perform(get("/flows/temp_hourly/versions/2")).andExpect(status().isOk())
                .andExpect(jsonPath("$.definition.name").value("Hourly temperature"));
        mvc.perform(get("/flows/temp_hourly/versions/9")).andExpect(status().isNotFound());
    }

    @Test
    void movingAFlowToAnotherDatasetTakesWriteAccessToBoth() throws Exception {
        callerIs(writer(new DataSets(false, Set.of(DS_DEMO))));
        when(repository.find("temp_hourly")).thenReturn(Optional.of(stored("temp_hourly", DS_DEMO, 1)));

        update(TestFlows.EXAMPLE.replace("\"dataSet\": \"ds_demo\"", "\"dataSet\": \"ds_other\""))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value("https://intellistream.ai/errors/dataset-forbidden"));
        verify(repository, never()).addVersion(anyLong(), anyInt(), anyLong(), anyString(), anyString(),
                anyString(), anyString(), anyString());
    }

    @Test
    void aChangeIsSavedAsTheNextVersionUnlessSomeoneSavedFirst() throws Exception {
        callerIs(writer(DataSets.ALL));
        StoredFlow flow = stored("temp_hourly", DS_DEMO, 1);
        when(repository.find("temp_hourly")).thenReturn(Optional.of(flow));
        when(repository.addVersion(eq(flow.id()), eq(1), eq(DS_DEMO), eq("ds_demo"), anyString(), eq("flow"),
                anyString(), eq("user-1"))).thenReturn(Optional.of(stored("temp_hourly", DS_DEMO, 2)));

        update(TestFlows.EXAMPLE).andExpect(status().isOk()).andExpect(jsonPath("$.version").value(2));

        when(repository.addVersion(anyLong(), anyInt(), anyLong(), anyString(), anyString(), anyString(), anyString(),
                anyString())).thenReturn(Optional.empty());
        update(TestFlows.EXAMPLE).andExpect(status().isConflict());
    }

    @Test
    void aChangeCannotRenameTheFlow() throws Exception {
        callerIs(writer(DataSets.ALL));
        when(repository.find("temp_hourly")).thenReturn(Optional.of(stored("temp_hourly", DS_DEMO, 1)));

        update(TestFlows.EXAMPLE.replace("\"externalId\": \"temp_hourly\"", "\"externalId\": \"other\""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields[0].field").value("#/externalId"));
    }

    @Test
    void validatingAnswersWithTheProblemsAndTheSeriesASaveWouldCreate() throws Exception {
        callerIs(writer(DataSets.ALL));

        mvc.perform(post("/flows/validate").contentType(MediaType.APPLICATION_JSON).content(TestFlows.EXAMPLE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.series[0]").value("ts_out_temp_f"));
        mvc.perform(post("/flows/validate").contentType(MediaType.APPLICATION_JSON)
                        .content(TestFlows.EXAMPLE.replace("\"dataSet\": \"ds_demo\"", "\"dataSet\": \"ds_gone\"")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.problems[0].field").value("#/dataSet"));
        verify(repository, never()).create(anyString(), anyLong(), anyString(), anyString(), anyString(), anyString(), isNull());
        verify(timeseries, never()).create(any(List.class));
    }

    @Test
    void aFlowWhoseDatasetWasDeletedIsSeenOnlyByWhoeverMayWriteEveryDataset() throws Exception {
        when(repository.find("orphaned")).thenReturn(Optional.of(orphan("orphaned")));
        when(repository.version(7, 1)).thenReturn(Optional.of(new StoredVersion(1, TestFlows.EXAMPLE,
                "user-1", Instant.EPOCH)));

        // Read access to the datasets that still exist does not reach it.
        callerIs(writer(new DataSets(false, Set.of(DS_DEMO))));
        mvc.perform(get("/flows/orphaned")).andExpect(status().isNotFound());

        callerIs(new Caller("user-1", false, true, false, new DataSets(false, Set.of(DS_DEMO)),
                DataSets.ALL));
        mvc.perform(get("/flows/orphaned")).andExpect(status().isOk())
                .andExpect(jsonPath("$.disabledReason").value("DATASET_DELETED"))
                .andExpect(jsonPath("$.enabled").value(false));

        DataSets readable = new DataSets(false, Set.of(DS_DEMO));
        when(repository.list(readable, true, null, null, FlowService.DEFAULT_LIMIT))
                .thenReturn(List.of(orphan("orphaned")));
        mvc.perform(get("/flows")).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].externalId").value("orphaned"));
    }

    @Test
    void anOrphanIsMovedBySavingItIntoAnotherDataset() throws Exception {
        when(repository.find("temp_hourly")).thenReturn(Optional.of(orphan("temp_hourly")));
        when(repository.addVersion(eq(7L), eq(1), eq(DS_DEMO), eq("ds_demo"), anyString(), eq("flow"), anyString(),
                eq("user-1"))).thenReturn(Optional.of(stored("temp_hourly", DS_DEMO, 2)));

        callerIs(writer(new DataSets(false, Set.of(DS_DEMO))));
        update(TestFlows.EXAMPLE).andExpect(status().isNotFound());

        callerIs(new Caller("user-1", false, true, true, new DataSets(false, Set.of(DS_DEMO)),
                DataSets.ALL));
        update(TestFlows.EXAMPLE).andExpect(status().isOk())
                .andExpect(jsonPath("$.dataSetId").value(String.valueOf(DS_DEMO)))
                .andExpect(jsonPath("$.disabledReason").doesNotExist());
    }

    @Test
    void deletingTakesWriteAccessToTheFlowsDatasetAndRemovesTheDeployersToken() throws Exception {
        when(repository.find("temp_hourly")).thenReturn(Optional.of(stored("temp_hourly", DS_DEMO, 1)));
        when(repository.identity(7)).thenReturn(Optional.of(new FlowRepository.IdentityState(
                FlowRepository.Identity.deployer("user-1"), true, null)));

        callerIs(reader(DataSets.ALL));
        mvc.perform(delete("/flows/temp_hourly")).andExpect(status().isForbidden());
        callerIs(writer(new DataSets(false, Set.of(DS_OTHER))));
        mvc.perform(delete("/flows/temp_hourly")).andExpect(status().isNotFound());
        callerIs(new Caller("user-1", false, true, true, DataSets.ALL, new DataSets(false, Set.of(DS_OTHER))));
        mvc.perform(delete("/flows/temp_hourly")).andExpect(status().isForbidden());
        verify(repository, never()).delete(anyLong());
        verify(offlineTokens, never()).remove(any(), anyLong());

        callerIs(writer(new DataSets(false, Set.of(DS_DEMO))));
        mvc.perform(delete("/flows/temp_hourly")).andExpect(status().isNoContent());
        verify(offlineTokens).remove(any(), eq(7L));
        verify(repository).delete(7);
    }

    @Test
    void anOrphanIsDeletedByWhoeverMayWriteEveryDataset() throws Exception {
        when(repository.find("orphaned")).thenReturn(Optional.of(orphan("orphaned")));
        when(repository.identity(7)).thenReturn(Optional.of(new FlowRepository.IdentityState(
                FlowRepository.Identity.client("ingest"), true, null)));

        callerIs(writer(new DataSets(false, Set.of(DS_DEMO))));
        mvc.perform(delete("/flows/orphaned")).andExpect(status().isNotFound());

        callerIs(new Caller("user-1", false, true, true, new DataSets(false, Set.of(DS_DEMO)),
                DataSets.ALL));
        mvc.perform(delete("/flows/orphaned")).andExpect(status().isNoContent());
        verify(repository).delete(7);
        // A registered client's credentials are the tenant's, not the flow's: nothing to remove.
        verify(offlineTokens, never()).remove(any(), anyLong());
    }

    @Test
    void anAdminMayDoEverything() throws Exception {
        callerIs(new Caller("admin-1", true, false, false, DataSets.ALL, DataSets.ALL));
        when(repository.list(DataSets.ALL, true, null, null, FlowService.DEFAULT_LIMIT)).thenReturn(List.of());

        mvc.perform(get("/flows")).andExpect(status().isOk());
        mvc.perform(post("/flows/validate").contentType(MediaType.APPLICATION_JSON).content(TestFlows.EXAMPLE))
                .andExpect(status().isOk()).andExpect(jsonPath("$.valid").value(true));
    }

    private org.springframework.test.web.servlet.ResultActions create(String body) throws Exception {
        return mvc.perform(post("/flows").contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private org.springframework.test.web.servlet.ResultActions update(String body) throws Exception {
        return mvc.perform(put("/flows/temp_hourly").contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private void callerIs(Caller caller) {
        when(access.current()).thenReturn(caller);
    }

    private static Caller reader(DataSets read) {
        return new Caller("user-1", false, true, false, read, new DataSets(false, Set.of()));
    }

    private static Caller writer(DataSets datasets) {
        return new Caller("user-1", false, true, true, datasets, datasets);
    }

    private static StoredFlow stored(String externalId, long dataSetId, int version) {
        return new StoredFlow(7, externalId, dataSetId, "ds_demo", "Hourly temperature", version, null,
                false, "user-1", Instant.EPOCH, "user-1", Instant.EPOCH, null, "flow");
    }

    /** A flow whose dataset was deleted, so the service switched it off. */
    private static StoredFlow orphan(String externalId) {
        return new StoredFlow(7, externalId, DS_GONE, "ds_gone", "Hourly temperature", 1, 1,
                false, "user-1", Instant.EPOCH, "user-1", Instant.EPOCH, FlowRepository.DATASET_DELETED, "flow");
    }

    private static DataSetModel dataSet(long id, String externalId) {
        DataSetModel ds = new DataSetModel();
        ds.setId(id);
        ds.setExternalId(externalId);
        return ds;
    }

    private static Timeseries series(String externalId) {
        Timeseries t = new Timeseries();
        t.setExternalId(externalId);
        return t;
    }

    private static <T> DataWrapper<T> wrap(List<T> items) {
        DataWrapper<T> wrapper = new DataWrapper<>();
        wrapper.setItems(items);
        return wrapper;
    }
}
