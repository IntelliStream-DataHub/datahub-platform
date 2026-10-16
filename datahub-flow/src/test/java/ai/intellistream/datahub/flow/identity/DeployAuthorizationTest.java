// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.flow.identity;

import ai.intellistream.datahub.flow.access.Caller;
import ai.intellistream.datahub.flow.access.Caller.DataSets;
import ai.intellistream.datahub.flow.flows.FlowRepository;
import ai.intellistream.datahub.flow.flows.FlowRepository.Identity;
import ai.intellistream.datahub.flow.flows.FlowRepository.StoredFlow;
import ai.intellistream.datahub.flow.flows.FlowService;
import ai.intellistream.datahub.flow.runs.RunService;
import ai.intellistream.datahub.flow.runs.RunService.DeploymentPlan;
import ai.intellistream.datahub.flow.runs.Schedule;
import ai.intellistream.datahub.flow.web.FlowProblems;
import ai.intellistream.datahub.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Deploying as yourself, from {@code authorize} to the callback: the deployment is written only for
 * a token that belongs to the person who started it, in the right organization, and that may write
 * the flow's dataset; every other ending revokes what Keycloak issued and stores nothing.
 */
class DeployAuthorizationTest {

    private static final String TENANT = "org-foo";
    private static final long FLOW_ID = 42;
    private static final long DATASET = 11;
    private static final Instant NOW = Instant.parse("2026-10-12T12:00:00Z");
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final StoredFlow FLOW = new StoredFlow(FLOW_ID, "temp_hourly", DATASET, "ds_demo",
            "Hourly", 3, null, false, "user-1", NOW, "user-1", NOW, null);
    private static final DeploymentPlan PLAN = new DeploymentPlan(FLOW_ID, "temp_hourly", 3,
            new Schedule(Schedule.MANUAL, null, null, null, null), 1, 2, Duration.ofMinutes(15));

    private final FlowService flowService = mock(FlowService.class);
    private final FlowRepository flows = mock(FlowRepository.class);
    private final RunService runService = mock(RunService.class);
    private final KeycloakTokens keycloak = mock(KeycloakTokens.class);
    private final FlowTokens offlineTokens = mock(FlowTokens.class);
    private final RegisteredClients clients = mock(RegisteredClients.class);
    private final JwtDecoder decoder = mock(JwtDecoder.class);
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private FakeMe api;
    private DeployAuthorization authorization;

    @BeforeEach
    void setUp() throws Exception {
        api = new FakeMe();
        authorization = new DeployAuthorization(flowService, flows, runService, keycloak, offlineTokens,
                clients, decoder, JSON, "http://localhost:8084/", "http://localhost:8080", api.url(), clock);
        when(keycloak.clientSecret()).thenReturn("changeme-flow");
        when(clients.organizationScope(TENANT)).thenReturn("organization:foo");
        when(flows.findById(FLOW_ID)).thenReturn(Optional.of(FLOW));
        when(runService.plan(eq(FLOW), any())).thenReturn(PLAN);
        when(keycloak.exchangeCode("the-code", "http://localhost:8084/flows/deployment/callback"))
                .thenReturn(new KeycloakTokens.Tokens("access-token", "offline-token", Duration.ofMinutes(5)));
        api.byToken.put("access-token", new FakeMe.Grants(false, List.of(), false, List.of(DATASET)));
        tokenIs("user-1", TENANT);
    }

    @AfterEach
    void tearDown() {
        api.close();
        TenantContext.clear();
    }

    private void tokenIs(String subject, String organizationId) {
        when(decoder.decode("access-token")).thenReturn(Jwt.withTokenValue("access-token")
                .header("alg", "RS256").subject(subject)
                .claim("organization", Map.of("foo", Map.of("id", organizationId)))
                .build());
    }

    private String state(String initiator, Instant expires) {
        return new DeployState(TENANT, FLOW_ID, 3, true, initiator, "n", expires)
                .sign(DeployState.key("changeme-flow"), JSON);
    }

    private DeployAuthorization.Outcome callback(String state) {
        return authorization.callback("the-code", state, null);
    }

    private static String location(DeployAuthorization.Outcome outcome) {
        assertThat(outcome).isInstanceOf(DeployAuthorization.Outcome.Redirect.class);
        return ((DeployAuthorization.Outcome.Redirect) outcome).location().toString();
    }

    private void nothingStored() {
        verify(offlineTokens, never()).store(anyString(), anyLong(), anyString());
        verify(runService, never()).apply(any(), anyBoolean(), any(), any());
    }

    @Test
    void authorizingSendsTheCallerToKeycloakWithASignedStateNamingThem() {
        TenantContext.setTenantId(TENANT);
        Caller caller = new Caller("user-1", false, true, true, DataSets.ALL,
                new DataSets(false, Set.of(DATASET)));
        when(flowService.requireWrite()).thenReturn(caller);
        when(flowService.visible(caller, "temp_hourly")).thenReturn(FLOW);
        when(keycloak.authorizationUrl(eq("http://localhost:8084/flows/deployment/callback"),
                eq("openid offline_access organization:foo"), anyString()))
                .thenAnswer(call -> URI.create("http://kc/auth?state=" + call.getArgument(2)));

        URI url = authorization.authorize("temp_hourly", null, null);

        String signed = url.getQuery().substring("state=".length());
        DeployState state = DeployState.verify(signed, DeployState.key("changeme-flow"), JSON, NOW);
        assertThat(state.initiator()).isEqualTo("user-1");
        assertThat(state.tenantId()).isEqualTo(TENANT);
        assertThat(state.version()).isEqualTo(3);
        assertThat(state.expires()).isEqualTo(NOW.plus(DeployAuthorization.STATE_LIFETIME));
    }

    @Test
    void authorizingTakesWriteAccessToTheFlowsDataset() {
        Caller caller = new Caller("user-1", false, true, true, DataSets.ALL, new DataSets(false, Set.of(99L)));
        when(flowService.requireWrite()).thenReturn(caller);
        when(flowService.visible(caller, "temp_hourly")).thenReturn(FLOW);

        assertThatThrownBy(() -> authorization.authorize("temp_hourly", null, null))
                .isInstanceOf(FlowProblems.ProblemException.class);
    }

    @Test
    void theInitiatorsTokenIsStoredAndTheDeploymentActsAsThem() {
        DeployAuthorization.Outcome outcome = callback(state("user-1", NOW.plusSeconds(60)));

        assertThat(location(outcome)).isEqualTo("http://localhost:8080/flows/temp_hourly?deployed=1");
        verify(offlineTokens).store(TENANT, FLOW_ID, "offline-token");
        verify(runService).apply(PLAN, true, Identity.deployer("user-1"), "user-1");
        verify(keycloak, never()).revoke(anyString());
    }

    @Test
    void someoneElsesSignInIsRevokedAndNothingIsStored() {
        tokenIs("user-2", TENANT);

        assertThat(location(callback(state("user-1", NOW.plusSeconds(60)))))
                .endsWith("/flows/temp_hourly?deployError=wrong-user");
        verify(keycloak).revoke("offline-token");
        nothingStored();
    }

    @Test
    void aTokenForAnotherOrganizationIsRevoked() {
        tokenIs("user-1", "org-bar");

        assertThat(location(callback(state("user-1", NOW.plusSeconds(60)))))
                .endsWith("deployError=wrong-organization");
        verify(keycloak).revoke("offline-token");
        nothingStored();
    }

    @Test
    void aDeployerWhoMayNotWriteTheDatasetIsRefused() {
        api.byToken.put("access-token", new FakeMe.Grants(false, List.of(), false, List.of(99L)));

        assertThat(location(callback(state("user-1", NOW.plusSeconds(60)))))
                .endsWith("deployError=dataset-forbidden");
        verify(keycloak).revoke("offline-token");
        nothingStored();
    }

    @Test
    void anInvalidTokenIsRevoked() {
        when(decoder.decode("access-token")).thenThrow(new BadJwtException("bad signature"));

        assertThat(location(callback(state("user-1", NOW.plusSeconds(60))))).endsWith("deployError=token-invalid");
        verify(keycloak).revoke("offline-token");
        nothingStored();
    }

    @Test
    void anExpiredOrForgedStateNeverReachesKeycloak() {
        assertThat(location(callback(state("user-1", NOW.minusSeconds(1)))))
                .isEqualTo("http://localhost:8080/flows?deployError=invalid-state");
        assertThat(location(callback(state("user-1", NOW.plusSeconds(60)) + "x")))
                .endsWith("deployError=invalid-state");
        verify(keycloak, never()).exchangeCode(anyString(), anyString());
        nothingStored();
    }

    @Test
    void aDeployerWithoutOfflineAccessIsToldSo() {
        when(keycloak.exchangeCode(anyString(), anyString()))
                .thenReturn(new KeycloakTokens.Tokens("access-token", null, Duration.ofMinutes(5)));

        assertThat(location(callback(state("user-1", NOW.plusSeconds(60)))))
                .endsWith("deployError=offline-access-missing");
        nothingStored();
    }

    @Test
    void aDeclinedConsentEndsQuietly() {
        DeployAuthorization.Outcome outcome = authorization.callback(null, state("user-1", NOW.plusSeconds(60)),
                "access_denied");

        assertThat(location(outcome)).endsWith("deployError=denied");
        verify(keycloak, never()).exchangeCode(anyString(), anyString());
        nothingStored();
    }

    @Test
    void withoutAConsoleTheCallbackAnswersWithAPageOfItsOwn() {
        DeployAuthorization standalone = new DeployAuthorization(flowService, flows, runService, keycloak,
                offlineTokens, clients, decoder, JSON, "http://localhost:8084", "", api.url(), clock);

        assertThat(standalone.callback("the-code", state("user-1", NOW.plusSeconds(60)), null))
                .isEqualTo(new DeployAuthorization.Outcome.Page(true,
                        "Flow temp_hourly is deployed. You can close this page."));
    }
}
