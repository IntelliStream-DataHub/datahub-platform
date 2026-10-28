// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.flow.identity;

import ai.intellistream.datahub.flow.access.CallerAccess;
import ai.intellistream.datahub.flow.flows.FlowRepository.Identity;
import ai.intellistream.datahub.flow.web.FlowProblems;
import ai.intellistream.datahub.models.me.MeGrants;
import ai.intellistream.datahub.sdk.client.DatahubClient;
import ai.intellistream.datahub.sdk.client.DatahubConfig;
import ai.intellistream.datahub.tenant.Tenant;
import ai.intellistream.datahub.tenant.TenantConfigWriter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The superset check: whoever has a flow's runs act as a registered client must hold every grant the
 * client holds, as datahub-api reports them for each one's own token.
 */
class RegisteredClientsTest {

    private static final String TENANT = "org-foo";
    private static final long DATASET = 11;

    private final TenantConfigWriter tenantConfig = mock(TenantConfigWriter.class);
    private final KeycloakTokens keycloak = mock(KeycloakTokens.class);
    private final CallerAccess callers = mock(CallerAccess.class);
    private FakeMe api;
    private RegisteredClients clients;

    @BeforeEach
    void setUp() throws Exception {
        api = new FakeMe();
        Tenant tenant = new Tenant();
        tenant.setOrganizationId(TENANT);
        tenant.setOrganizationName("foo");
        clients = new RegisteredClients(id -> TENANT.equals(id) ? tenant : null, tenantConfig, keycloak,
                callers, api.url());
        when(tenantConfig.current("foo")).thenReturn(Map.of(
                "flow.client.ingest.client-id", "datahub-ingest",
                "flow.client.ingest.client-secret", "ingest-secret"));
        when(keycloak.clientCredentials("datahub-ingest", "ingest-secret", "openid organization:foo"))
                .thenReturn(new KeycloakTokens.Tokens("client-token", null, Duration.ofMinutes(5)));
        when(callers.client()).thenReturn(DatahubClient.create(DatahubConfig.builder()
                .baseUrl(api.url()).token("caller-token").build()));
    }

    @AfterEach
    void tearDown() {
        api.close();
    }

    private void client(boolean admin, List<String> groups, boolean writes) {
        api.byToken.put("client-token", new FakeMe.Grants(admin, groups, false,
                writes ? List.of(DATASET) : List.of()));
    }

    private void caller(boolean admin, List<String> groups) {
        api.byToken.put("caller-token", new FakeMe.Grants(admin, groups, false, List.of()));
    }

    @Test
    void theRegisteredClientsAreListedByNameAlone() {
        when(tenantConfig.current("foo")).thenReturn(Map.of(
                "flow.client.reporting.client-id", "datahub-reporting",
                "flow.client.reporting.client-secret", "reporting-secret",
                "flow.client.ingest.client-id", "datahub-ingest",
                "flow.client.ingest.client-secret", "ingest-secret",
                "flow.secret.sftp-password", "hunter2"));

        assertThat(clients.names(TENANT)).containsExactly("ingest", "reporting");
    }

    @Test
    void aCallerHoldingEveryGrantOfTheClientMayAssignIt() {
        client(false, List.of("/datasets/ds_demo/write"), true);
        caller(false, List.of("/datasets/ds_demo/write", "/flows/write"));

        assertThat(clients.assign(TENANT, "ingest", DATASET)).isEqualTo(Identity.client("ingest"));
    }

    @Test
    void aClientHoldingAGrantTheCallerLacksIsRefusedNamingIt() {
        client(false, List.of("/datasets/*/write"), true);
        caller(false, List.of("/datasets/ds_demo/write", "/flows/write"));

        assertThatThrownBy(() -> clients.assign(TENANT, "ingest", DATASET))
                .isInstanceOf(FlowProblems.ProblemException.class)
                .hasMessageContaining("/datasets/*/write");
    }

    @Test
    void aClientThatCannotWriteTheFlowsDatasetIsRefused() {
        client(false, List.of(), false);
        caller(false, List.of("/flows/write"));

        assertThatThrownBy(() -> clients.assign(TENANT, "ingest", DATASET))
                .isInstanceOfSatisfying(FlowProblems.ProblemException.class, e ->
                        assertThat(e.problem().getStatus()).isEqualTo(403));
    }

    @Test
    void anUnregisteredClientOrOneKeycloakRefusesIsABadRequest() {
        assertThatThrownBy(() -> clients.assign(TENANT, "absent", DATASET))
                .isInstanceOfSatisfying(FlowProblems.ProblemException.class, e ->
                        assertThat(e.problem().getStatus()).isEqualTo(400));
        assertThatThrownBy(() -> clients.assign(TENANT, "../llm", DATASET))
                .isInstanceOfSatisfying(FlowProblems.ProblemException.class, e ->
                        assertThat(e.problem().getStatus()).isEqualTo(400));
    }

    @Test
    void theAdminCasesOfTheSupersetCheck() {
        MeGrants plain = new MeGrants(false, List.of("/a", "/b"));
        MeGrants admin = new MeGrants(true, List.of());

        // An admin may assign anything; an admin client only an admin may.
        assertThat(RegisteredClients.missing(admin, admin)).isEmpty();
        assertThat(RegisteredClients.missing(plain, admin)).isEmpty();
        assertThat(RegisteredClients.missing(admin, plain)).containsExactly("DATAHUB_ADMIN");
        // Otherwise, every group the client holds and the caller does not, sorted.
        assertThat(RegisteredClients.missing(new MeGrants(false, List.of("/c", "/a", "/b")),
                new MeGrants(false, List.of("/a")))).containsExactly("/b", "/c");
        assertThat(RegisteredClients.missing(new MeGrants(false, List.of()), plain)).isEmpty();
    }
}
