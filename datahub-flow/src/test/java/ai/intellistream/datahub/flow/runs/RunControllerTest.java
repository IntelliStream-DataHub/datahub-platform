// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.flow.runs;

import ai.intellistream.datahub.api.responses.DataWrapper;
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
import ai.intellistream.datahub.flow.runs.RunRepository.NewPreview;
import ai.intellistream.datahub.flow.runs.RunRepository.NewRun;
import ai.intellistream.datahub.flow.runs.RunRepository.StoredRun;
import ai.intellistream.datahub.flow.web.FlowProblems;
import ai.intellistream.datahub.models.DataSetModel;
import ai.intellistream.datahub.sdk.client.DatahubClient;
import ai.intellistream.datahub.sdk.client.DatasetService;
import ai.intellistream.datahub.tenant.Tenant;
import ai.intellistream.datahub.tenant.TenantConfigService;
import ai.intellistream.datahub.tenant.TenantConfigWriter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
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
    private final DatahubClient api = mock(DatahubClient.class);
    private final DatasetService datasets = mock(DatasetService.class);
    private MockMvc mvc;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @BeforeEach
    void setUp() {
        FlowValidator validator = new FlowValidator(TestFlows.builtIns(), FlowValidator.Limits.DEFAULTS);
        TransactionTemplate transaction = new TransactionTemplate(mock(PlatformTransactionManager.class));
        TenantConfigService tenants = mock(TenantConfigService.class);
        Tenant tenant = new Tenant();
        tenant.setOrganizationName("foo");
        when(tenants.getConfig(any())).thenReturn(tenant);
        TenantConfigWriter tenantConfig = mock(TenantConfigWriter.class);
        when(tenantConfig.current("foo")).thenReturn(Map.of());
        when(access.client()).thenReturn(api);
        when(api.datasets()).thenReturn(datasets);
        DataSetModel demo = new DataSetModel();
        demo.setId(DS_DEMO);
        demo.setExternalId("ds_demo");
        DataWrapper<DataSetModel> found = new DataWrapper<>();
        found.setItems(List.of(demo));
        when(datasets.byIds(any())).thenReturn(found);
        FlowService flowService = new FlowService(flows, access, validator, tenants, tenantConfig,
                transaction, JSON, offlineTokens);
        RunService service = new RunService(flowService, flows, runs, coordinator,
                new FlowCompiler(validator), transaction, JSON, clients, offlineTokens);
        mvc = MockMvcBuilders.standaloneSetup(new RunController(service))
                .setControllerAdvice(new FlowProblems())
                .build();

        when(flows.find("temp_hourly")).thenReturn(Optional.of(new StoredFlow(FLOW_ID, "temp_hourly",
                DS_DEMO, "ds_demo", "Hourly temperature", 1, null, false, "user-1", Instant.EPOCH,
                "user-1", Instant.EPOCH, null)));
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
                any(), any(), anyString(), any(), any());
    }

    @Test
    void aFlowWithNoIdentityYetIsDeployedThroughTheAuthorizationOrARegisteredClient() throws Exception {
        callerIs(writer());
        when(flows.identity(FLOW_ID)).thenReturn(Optional.empty());

        deploy("{}").andExpect(status().isConflict());
        verify(flows, never()).deploy(anyLong(), anyInt(), anyBoolean(), anyString(), anyInt(), anyInt(),
                any(), any(), anyString(), any(), any());
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
                eq(Duration.ofMinutes(15)), isNull(), eq("user-1"), isNull(), isNull());
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

        verify(flows).deploy(eq(FLOW_ID), eq(1), eq(true), anyString(), eq(1), eq(2), any(),
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
                any(), any(), anyString(), any(), any());
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
        when(runs.list(readable, null, false, null, null, 100)).thenReturn(List.of());

        mvc.perform(get("/runs")).andExpect(status().isOk()).andExpect(jsonPath("$.items").isEmpty());
        verify(runs).list(readable, null, false, null, null, 100);

        // Previews are shown apart, so only on request.
        mvc.perform(get("/runs?preview=true")).andExpect(status().isOk());
        verify(runs).list(readable, null, true, null, null, 100);
    }

    @Test
    void theOverviewIsLimitedToReadableDatasetsAndPagedByFlow() throws Exception {
        DataSets readable = new DataSets(false, Set.of(DS_DEMO));
        callerIs(new Caller("user-1", false, true, false, readable, readable));
        when(runs.now()).thenReturn(Instant.parse("2026-10-16T12:00:00Z"));
        when(runs.board(readable, null, 100)).thenReturn(List.of());

        mvc.perform(get("/runs/overview")).andExpect(status().isOk()).andExpect(jsonPath("$.items").isEmpty());
        verify(runs).board(readable, null, 100);

        callerIs(new Caller("user-1", false, false, false, new DataSets(false, Set.of()),
                new DataSets(false, Set.of())));
        mvc.perform(get("/runs/overview")).andExpect(status().isForbidden());
    }

    // ---------------------------------------------------------------- previews

    @Test
    void previewingTakesTheWriteGrantAndWriteAccessToTheDataset() throws Exception {
        tokenExpiresIn(Duration.ofMinutes(10));
        callerIs(new Caller("user-1", false, true, false, DataSets.ALL, DataSets.ALL));
        preview("{\"flow\":\"temp_hourly\"}").andExpect(status().isForbidden());

        callerIs(new Caller("user-1", false, true, true, DataSets.ALL, new DataSets(false, Set.of(99L))));
        preview("{\"flow\":\"temp_hourly\"}").andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value("https://intellistream.ai/errors/dataset-forbidden"));
        preview("{\"definition\":" + TestFlows.EXAMPLE + "}").andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value("https://intellistream.ai/errors/dataset-forbidden"));
        verify(runs, never()).insertPreview(any());
    }

    @Test
    void aPreviewIsOfASavedFlowOrOfADefinitionNotBoth() throws Exception {
        tokenExpiresIn(Duration.ofMinutes(10));
        callerIs(writer());

        preview("{}").andExpect(status().isBadRequest());
        preview("{\"flow\":\"temp_hourly\",\"definition\":" + TestFlows.EXAMPLE + "}")
                .andExpect(status().isBadRequest());
        verify(runs, never()).insertPreview(any());
    }

    @Test
    void aPreviewOfASavedVersionRunsHereAsTheCallerOverTheLastHour() throws Exception {
        tokenExpiresIn(Duration.ofMinutes(10));
        callerIs(writer());
        UUID id = UUID.randomUUID();
        when(runs.insertPreview(any())).thenReturn(id);
        when(coordinator.claimPreview(any(), eq(id), any())).thenReturn(true);
        when(runs.find(id)).thenReturn(Optional.of(stored(id, DS_DEMO, "RUNNING")));

        // A flow need not be deployed to be previewed.
        preview("{\"flow\":\"temp_hourly\"}").andExpect(status().isAccepted())
                .andExpect(jsonPath("$.id").value(id.toString()));

        ArgumentCaptor<NewPreview> created = ArgumentCaptor.forClass(NewPreview.class);
        verify(runs).insertPreview(created.capture());
        NewPreview p = created.getValue();
        assertThat(p.flowId()).isEqualTo(FLOW_ID);
        assertThat(p.version()).isEqualTo(1);
        assertThat(p.definition()).isNull();
        assertThat(p.window()).isEqualTo(new TimeWindow(Instant.parse("2026-10-12T10:06:00Z"),
                Instant.parse("2026-10-12T11:06:00Z")));
        assertThat(p.triggeredBy()).isEqualTo("user-1");
        // The token has ten minutes; the flow's fifteen-minute timeout is cut to what it has left.
        assertThat(p.timeout()).isLessThanOrEqualTo(Duration.ofMinutes(10).minusSeconds(30))
                .isGreaterThan(Duration.ofMinutes(9));

        ArgumentCaptor<java.util.function.Supplier<String>> tokens = ArgumentCaptor.captor();
        verify(coordinator).claimPreview(any(), eq(id), tokens.capture());
        assertThat(tokens.getValue().get()).isEqualTo("caller-token");
    }

    @Test
    void anUnsavedDefinitionIsValidatedAndItsDatasetResolvedButNothingIsSaved() throws Exception {
        tokenExpiresIn(Duration.ofHours(1));
        callerIs(writer());

        preview("{\"definition\":{\"schemaVersion\":1,\"externalId\":\"x\",\"dataSet\":\"ds_demo\","
                + "\"name\":\"n\",\"processors\":[{\"id\":\"s\",\"type\":\"no.such.processor\"}],"
                + "\"connections\":[]}}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://intellistream.ai/errors/validation-failed"));
        verify(runs, never()).insertPreview(any());

        UUID id = UUID.randomUUID();
        when(runs.insertPreview(any())).thenReturn(id);
        when(coordinator.claimPreview(any(), eq(id), any())).thenReturn(true);
        when(runs.find(id)).thenReturn(Optional.of(stored(id, DS_DEMO, "RUNNING")));
        preview("{\"definition\":" + TestFlows.EXAMPLE + ",\"window\":{\"start\":\"2026-10-01T00:00:00Z\","
                + "\"end\":\"2026-10-01T06:00:00Z\"}}").andExpect(status().isAccepted());

        ArgumentCaptor<NewPreview> created = ArgumentCaptor.forClass(NewPreview.class);
        verify(runs).insertPreview(created.capture());
        assertThat(created.getValue().flowId()).isNull();
        assertThat(created.getValue().dataSetId()).isEqualTo(DS_DEMO);
        assertThat(created.getValue().definition()).contains("\"externalId\":\"temp_hourly\"");
        assertThat(created.getValue().window().start()).isEqualTo(Instant.parse("2026-10-01T00:00:00Z"));
        // Saving is not previewing: no flow is created, and no declared series either.
        verify(flows, never()).create(anyString(), anyLong(), anyString(), anyString(), anyString(),
                anyString());
        verify(api, never()).timeseries();
    }

    @Test
    void aTokenAboutToExpireCannotRunAPreview() throws Exception {
        tokenExpiresIn(Duration.ofSeconds(80));
        callerIs(writer());

        preview("{\"flow\":\"temp_hourly\"}").andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.type").value("https://intellistream.ai/errors/token-expiring"));
        verify(runs, never()).insertPreview(any());
    }

    @Test
    void aPreviewThisInstanceHasNoRoomForIsCancelledAndRefused() throws Exception {
        tokenExpiresIn(Duration.ofMinutes(10));
        callerIs(writer());
        UUID id = UUID.randomUUID();
        when(runs.insertPreview(any())).thenReturn(id);
        when(coordinator.claimPreview(any(), eq(id), any())).thenReturn(false);

        preview("{\"flow\":\"temp_hourly\"}").andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.type").value("https://intellistream.ai/errors/busy"));
        verify(runs).cancelPending(eq(id), eq(RunCoordinator.NO_CAPACITY), anyString());
    }

    @Test
    void aPreviewIsNotReRun() throws Exception {
        callerIs(writer());
        UUID id = UUID.randomUUID();
        when(runs.find(id)).thenReturn(Optional.of(new StoredRun(id, FLOW_ID, "temp_hourly", DS_DEMO, 1,
                "SUCCEEDED", 1, 1, Instant.EPOCH, false, true, false, Duration.ofMinutes(15),
                null, null, "{}", null, "preview", "user-1", "user-1", Instant.EPOCH, null, null,
                null, null, null)));

        mvc.perform(post("/runs/" + id + "/rerun")).andExpect(status().isBadRequest());
        verify(runs, never()).insert(any());
    }

    @Test
    void failedRunsAreRequeuedInOneGoAndTheDispatcherIsWokenOnce() throws Exception {
        callerIs(writer());
        when(flows.deployment(FLOW_ID)).thenReturn(Optional.of(deployment(true)));
        UUID one = UUID.randomUUID();
        UUID two = UUID.randomUUID();
        when(runs.requeueFailed(eq(FLOW_ID), eq(1), eq(2), eq(Duration.ofMinutes(15)), eq("user-1"),
                eq("user-1"), eq(List.of(one, two)))).thenReturn(2);

        rerunFailed("{\"runs\":[\"" + one + "\",\"" + two + "\"]}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.requeued").value(2));
        verify(coordinator).poke(any());
        verify(runs, never()).insert(any());

        // Nothing queued, nobody woken: asking again for runs already redone is harmless.
        when(runs.requeueFailed(anyLong(), anyInt(), anyInt(), any(), anyString(), anyString(), any()))
                .thenReturn(0);
        rerunFailed("{\"runs\":[\"" + one + "\"]}").andExpect(jsonPath("$.requeued").value(0));
        verify(coordinator).poke(any());
    }

    @Test
    void requeuingTakesWriteAccessAnEnabledFlowAndASaneNumberOfRuns() throws Exception {
        callerIs(new Caller("user-1", false, true, true, DataSets.ALL, new DataSets(false, Set.of(99L))));
        rerunFailed("{\"runs\":[\"" + UUID.randomUUID() + "\"]}").andExpect(status().isForbidden());

        callerIs(writer());
        when(flows.deployment(FLOW_ID)).thenReturn(Optional.of(deployment(false)));
        rerunFailed("{\"runs\":[\"" + UUID.randomUUID() + "\"]}").andExpect(status().isConflict());

        when(flows.deployment(FLOW_ID)).thenReturn(Optional.of(deployment(true)));
        rerunFailed("{\"runs\":[]}").andExpect(status().isBadRequest());
        String tooMany = "{\"runs\":[" + String.join(",", java.util.Collections.nCopies(10_001,
                "\"" + UUID.randomUUID() + "\"")) + "]}";
        rerunFailed(tooMany).andExpect(status().isBadRequest());
        verify(runs, never()).requeueFailed(anyLong(), anyInt(), anyInt(), any(), anyString(), anyString(), any());
    }

    // ---------------------------------------------------------------- fixtures

    private org.springframework.test.web.servlet.ResultActions rerunFailed(String body) throws Exception {
        return mvc.perform(post("/flows/temp_hourly/runs/rerun").contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private org.springframework.test.web.servlet.ResultActions preview(String body) throws Exception {
        return mvc.perform(post("/previews").contentType(MediaType.APPLICATION_JSON).content(body));
    }

    /** The caller's token, as the security chain leaves it, expiring {@code left} from now. */
    private static void tokenExpiresIn(Duration left) {
        Instant now = Instant.now();
        Jwt jwt = Jwt.withTokenValue("caller-token").header("alg", "none").subject("user-1")
                .issuedAt(now.minusSeconds(60)).expiresAt(now.plus(left)).build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
    }

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
                "deployer", "user-1", null, "ok", null, "user-1", Instant.EPOCH,
                null, null, null);
    }

    private static StoredRun stored(UUID id, long dataSetId, String status) {
        return new StoredRun(id, FLOW_ID, "temp_hourly", dataSetId, 1, status, 1, 2, Instant.EPOCH,
                false, false, false, Duration.ofMinutes(15), null, null, "{}", null, "manual",
                "user-1", "user-1", Instant.EPOCH, null, null, null, null, null);
    }
}
