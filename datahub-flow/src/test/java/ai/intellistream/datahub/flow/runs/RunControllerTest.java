// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.flow.runs;

import ai.intellistream.datahub.flow.access.Caller;
import ai.intellistream.datahub.flow.access.Caller.DataSets;
import ai.intellistream.datahub.flow.access.CallerAccess;
import ai.intellistream.datahub.flow.api.TimeWindow;
import ai.intellistream.datahub.flow.definition.FlowValidator;
import ai.intellistream.datahub.flow.engine.FlowCompiler;
import ai.intellistream.datahub.flow.flows.FlowRepository;
import ai.intellistream.datahub.flow.flows.FlowRepository.Deployment;
import ai.intellistream.datahub.flow.flows.FlowRepository.Identity;
import ai.intellistream.datahub.flow.flows.FlowRepository.IdentityState;
import ai.intellistream.datahub.flow.flows.FlowRepository.StoredFlow;
import ai.intellistream.datahub.flow.flows.FlowRepository.StoredVersion;
import ai.intellistream.datahub.flow.flows.FlowService;
import ai.intellistream.datahub.flow.flows.TestFlows;
import ai.intellistream.datahub.flow.identity.FlowTokens;
import ai.intellistream.datahub.flow.identity.RegisteredClients;
import ai.intellistream.datahub.flow.runs.RunRepository.NewRun;
import ai.intellistream.datahub.flow.runs.RunRepository.StoredRun;
import ai.intellistream.datahub.flow.web.FlowProblems;
import ai.intellistream.datahub.tenant.TenantConfigService;
import ai.intellistream.datahub.tenant.TenantConfigWriter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Deploying and running through the REST interface, with the real {@link RunService} and
 * {@link FlowService} and the database and the coordinator faked: who may do what, and what each
 * refusal answers.
 */
class RunControllerTest {

    private static final long DS_DEMO = 11;
    private static final long FLOW_ID = 7;
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final FlowRepository flows = mock(FlowRepository.class);
    private final RunRepository runs = mock(RunRepository.class);
    private final RunCoordinator coordinator = mock(RunCoordinator.class);
    private final CallerAccess access = mock(CallerAccess.class);
    private final RegisteredClients clients = mock(RegisteredClients.class);
    private final FlowTokens offlineTokens = mock(FlowTokens.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        FlowValidator validator = new FlowValidator(TestFlows.builtIns(), FlowValidator.Limits.DEFAULTS);
        TransactionTemplate transaction = new TransactionTemplate(mock(PlatformTransactionManager.class));
        FlowService flowService = new FlowService(flows, access, validator, mock(TenantConfigService.class),
                mock(TenantConfigWriter.class), transaction, JSON);
        RunService service = new RunService(flowService, flows, runs, coordinator,
                new FlowCompiler(validator), transaction, JSON, clients, offlineTokens);
        mvc = MockMvcBuilders.standaloneSetup(new RunController(service))
                .setControllerAdvice(new FlowProblems())
                .build();

        when(flows.find("temp_hourly")).thenReturn(Optional.of(new StoredFlow(FLOW_ID, "temp_hourly",
                DS_DEMO, "ds_demo", "Hourly temperature", 1, null, false, "user-1", Instant.EPOCH,
                "user-1", Instant.EPOCH)));
        when(flows.version(FLOW_ID, 1)).thenReturn(Optional.of(new StoredVersion(1, TestFlows.EXAMPLE,
                "user-1", Instant.EPOCH)));
        when(runs.now()).thenReturn(Instant.parse("2026-10-12T11:06:00Z"));
    }

    @Test
    void deployingTakesTheWriteGrantAndWriteAccessToTheDataset() throws Exception {
        callerIs(new Caller("user-1", false, true, false, DataSets.ALL, DataSets.ALL));
        deploy("{}").andExpect(status().isForbidden());

        callerIs(new Caller("user-1", false, true, true, DataSets.ALL, new DataSets(false, Set.of(99L))));
        deploy("{}").andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value("https://intellistream.ai/errors/dataset-forbidden"));
        verify(flows, never()).deploy(anyLong(), anyInt(), anyBoolean(), anyString(), anyInt(), anyInt(),
                any(), anyLong(), any(), anyString(), any(), any());
    }

    @Test
    void aFlowWithNoIdentityYetIsDeployedThroughTheAuthorizationOrARegisteredClient() throws Exception {
        callerIs(writer());
        when(flows.identity(FLOW_ID)).thenReturn(Optional.empty());

        deploy("{}").andExpect(status().isConflict());
        verify(flows, never()).deploy(anyLong(), anyInt(), anyBoolean(), anyString(), anyInt(), anyInt(),
                any(), anyLong(), any(), anyString(), any(), any());
    }

    @Test
    void redeployingSwitchesTheVersionAndKeepsTheIdentity() throws Exception {
        callerIs(writer());
        when(flows.identity(FLOW_ID)).thenReturn(Optional.of(
                new IdentityState(Identity.deployer("user-9"), true, null)));
        when(flows.deployment(FLOW_ID)).thenReturn(Optional.of(deployment(true)));

        deploy("{}").andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1));

        ArgumentCaptor<String> trigger = ArgumentCaptor.forClass(String.class);
        verify(flows).deploy(eq(FLOW_ID), eq(1), eq(true), trigger.capture(), eq(1), eq(2),
                eq(Duration.ofMinutes(15)), eq(512L * 1024 * 1024), isNull(), eq("user-1"), isNull(),
                isNull());
        assertThat(trigger.getValue()).isEqualTo("{\"type\":\"manual\"}");
        verify(offlineTokens, never()).remove(any(), anyLong());
    }

    @Test
    void runningAsARegisteredClientTakesTheCheckAndRetiresTheDeployersToken() throws Exception {
        callerIs(writer());
        when(flows.identity(FLOW_ID)).thenReturn(Optional.of(
                new IdentityState(Identity.deployer("user-9"), true, null)));
        when(flows.deployment(FLOW_ID)).thenReturn(Optional.of(deployment(true)));
        when(clients.assign(any(), eq("ingest"), eq(DS_DEMO))).thenReturn(Identity.client("ingest"));

        deploy("{\"runAs\":\"ingest\"}").andExpect(status().isOk());

        verify(flows).deploy(eq(FLOW_ID), eq(1), eq(true), anyString(), eq(1), eq(2), any(), anyLong(),
                eq(Identity.client("ingest")), eq("user-1"), isNull(), isNull());
        verify(offlineTokens).remove(any(), eq(FLOW_ID));
    }

    @Test
    void aClientTheCallerMayNotAssignLeavesTheDeploymentAlone() throws Exception {
        callerIs(writer());
        when(flows.identity(FLOW_ID)).thenReturn(Optional.of(
                new IdentityState(Identity.deployer("user-9"), true, null)));
        when(clients.assign(any(), eq("admin-client"), eq(DS_DEMO)))
                .thenThrow(FlowProblems.forbidden("Client admin-client holds grants you do not"));

        deploy("{\"runAs\":\"admin-client\"}").andExpect(status().isForbidden());
        verify(flows, never()).deploy(anyLong(), anyInt(), anyBoolean(), anyString(), anyInt(), anyInt(),
                any(), anyLong(), any(), anyString(), any(), any());
        verify(offlineTokens, never()).remove(any(), anyLong());
    }

    @Test
    void aFlowThatIsNotDeployedOrIsSwitchedOffCannotBeRun() throws Exception {
        callerIs(writer());
        when(flows.deployment(FLOW_ID)).thenReturn(Optional.empty());
        start("{}").andExpect(status().isConflict());

        when(flows.deployment(FLOW_ID)).thenReturn(Optional.of(deployment(false)));
        start("{}").andExpect(status().isConflict());
        verify(runs, never()).insert(any());
    }

    @Test
    void aManualRunNeedsAWindowWhenItsTriggerHasNone() throws Exception {
        callerIs(writer());
        when(flows.deployment(FLOW_ID)).thenReturn(Optional.of(deployment(true)));

        start("{}").andExpect(status().isBadRequest());
        start("{\"window\":{\"start\":\"2026-10-12T11:00:00Z\",\"end\":\"2026-10-12T10:00:00Z\"}}")
                .andExpect(status().isBadRequest());
        verify(runs, never()).insert(any());
    }

    @Test
    void aManualRunIsCreatedAndStartedHereWhenThereIsRoom() throws Exception {
        callerIs(writer());
        when(flows.deployment(FLOW_ID)).thenReturn(Optional.of(deployment(true)));
        UUID id = UUID.randomUUID();
        when(runs.insert(any())).thenReturn(id);
        when(runs.find(id)).thenReturn(Optional.of(stored(id, DS_DEMO, "PENDING")));

        start("{\"window\":{\"start\":\"2026-10-12T10:00:00Z\",\"end\":\"2026-10-12T11:00:00Z\"},"
                + "\"parameters\":{\"limit\":120}}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.flow").value("temp_hourly"));

        ArgumentCaptor<NewRun> created = ArgumentCaptor.forClass(NewRun.class);
        verify(runs).insert(created.capture());
        assertThat(created.getValue().window()).isEqualTo(new TimeWindow(
                Instant.parse("2026-10-12T10:00:00Z"), Instant.parse("2026-10-12T11:00:00Z")));
        assertThat(created.getValue().parameters()).isEqualTo("{\"limit\":120}");
        assertThat(created.getValue().trigger()).isEqualTo("manual");
        assertThat(created.getValue().runAs()).isEqualTo("user-1");
        verify(coordinator).claim(any(), eq(id));
    }

    @Test
    void aRunInADatasetTheCallerCannotReadDoesNotExistForThem() throws Exception {
        callerIs(new Caller("user-2", false, true, true, new DataSets(false, Set.of(99L)),
                new DataSets(false, Set.of(99L))));
        UUID id = UUID.randomUUID();
        when(runs.find(id)).thenReturn(Optional.of(stored(id, DS_DEMO, "RUNNING")));

        mvc.perform(get("/runs/" + id)).andExpect(status().isNotFound());
        mvc.perform(post("/runs/" + id + "/cancel")).andExpect(status().isNotFound());
    }

    @Test
    void cancellingARunThatHasEndedIsAConflict() throws Exception {
        callerIs(writer());
        UUID id = UUID.randomUUID();
        when(runs.find(id)).thenReturn(Optional.of(stored(id, DS_DEMO, "SUCCEEDED")));

        mvc.perform(post("/runs/" + id + "/cancel")).andExpect(status().isConflict());

        when(runs.cancelIfPending(id, "user-1")).thenReturn(true);
        mvc.perform(post("/runs/" + id + "/cancel")).andExpect(status().isOk());
    }

    @Test
    void theRunListIsLimitedToReadableDatasets() throws Exception {
        DataSets readable = new DataSets(false, Set.of(DS_DEMO));
        callerIs(new Caller("user-1", false, true, false, readable, readable));
        when(runs.list(readable, null, null, null, 100)).thenReturn(List.of());

        mvc.perform(get("/runs")).andExpect(status().isOk()).andExpect(jsonPath("$.items").isEmpty());
        verify(runs).list(readable, null, null, null, 100);
    }

    // ---------------------------------------------------------------- fixtures

    private org.springframework.test.web.servlet.ResultActions deploy(String body) throws Exception {
        return mvc.perform(put("/flows/temp_hourly/deployment").contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private org.springframework.test.web.servlet.ResultActions start(String body) throws Exception {
        return mvc.perform(post("/flows/temp_hourly/runs").contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private void callerIs(Caller caller) {
        when(access.current()).thenReturn(caller);
    }

    private static Caller writer() {
        DataSets demo = new DataSets(false, Set.of(DS_DEMO));
        return new Caller("user-1", false, true, true, demo, demo);
    }

    private static Deployment deployment(boolean enabled) {
        return new Deployment(1, enabled, null, "{\"type\":\"manual\"}", 1, 2, Duration.ofMinutes(15),
                512L * 1024 * 1024, "deployer", "user-1", null, "ok", null, "user-1", Instant.EPOCH,
                null, null, null);
    }

    private static StoredRun stored(UUID id, long dataSetId, String status) {
        return new StoredRun(id, FLOW_ID, "temp_hourly", dataSetId, 1, status, 1, 2, Instant.EPOCH,
                false, false, false, 1024, Duration.ofMinutes(15), null, null, "{}", null, "manual",
                "user-1", "user-1", Instant.EPOCH, null, null, null, null, null);
    }
}
