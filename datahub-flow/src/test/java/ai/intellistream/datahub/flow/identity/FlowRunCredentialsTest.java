// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.flow.identity;

import ai.intellistream.datahub.flow.flows.FlowRepository;
import ai.intellistream.datahub.flow.flows.FlowRepository.Identity;
import ai.intellistream.datahub.flow.flows.FlowRepository.IdentityState;
import ai.intellistream.datahub.flow.runs.RunCredentials.RunIdentityException;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * A run's tokens, as its deployer or as a registered client: taken once and shared until shortly
 * before they expire, and a refusal turns the deployment into one that needs re-authorisation
 * rather than a failure to retry.
 */
class FlowRunCredentialsTest {

    private static final String TENANT = "org-foo";
    private static final long FLOW = 42;

    private final FlowRepository flows = mock(FlowRepository.class);
    private final FlowTokens offlineTokens = mock(FlowTokens.class);
    private final RegisteredClients clients = mock(RegisteredClients.class);
    private final KeycloakTokens keycloak = mock(KeycloakTokens.class);
    private final AtomicLong now = new AtomicLong();
    private final FlowRunCredentials credentials =
            new FlowRunCredentials(flows, offlineTokens, clients, keycloak, now::get);

    private void deployedBy(String subject) {
        when(flows.identity(FLOW)).thenReturn(Optional.of(new IdentityState(Identity.deployer(subject), true, null)));
        when(offlineTokens.get(TENANT, FLOW)).thenReturn("offline-1");
    }

    @Test
    void anAccessTokenIsSharedByRunsUntilShortlyBeforeItExpires() {
        deployedBy("user-1");
        when(keycloak.refresh("offline-1"))
                .thenReturn(new KeycloakTokens.Tokens("access-1", "offline-1", Duration.ofMinutes(5)))
                .thenReturn(new KeycloakTokens.Tokens("access-2", "offline-1", Duration.ofMinutes(5)));

        Supplier<String> first = credentials.tokens(TENANT, FLOW);
        Supplier<String> second = credentials.tokens(TENANT, FLOW);
        assertThat(first.get()).isEqualTo("access-1");
        assertThat(second.get()).isEqualTo("access-1");
        verify(keycloak, times(1)).refresh("offline-1");

        now.addAndGet(Duration.ofMinutes(5).minus(FlowRunCredentials.MARGIN).toNanos());
        assertThat(first.get()).isEqualTo("access-2");
        verify(keycloak, times(2)).refresh("offline-1");
    }

    @Test
    void aShortLivedAccessTokenIsSharedForHalfItsLifetime() {
        deployedBy("user-1");
        when(keycloak.refresh("offline-1"))
                .thenReturn(new KeycloakTokens.Tokens("access-1", "offline-1", Duration.ofSeconds(60)))
                .thenReturn(new KeycloakTokens.Tokens("access-2", "offline-1", Duration.ofSeconds(60)));
        Supplier<String> tokens = credentials.tokens(TENANT, FLOW);

        now.addAndGet(Duration.ofSeconds(29).toNanos());
        assertThat(tokens.get()).isEqualTo("access-1");
        now.addAndGet(Duration.ofSeconds(1).toNanos());
        assertThat(tokens.get()).isEqualTo("access-2");
    }

    @Test
    void aRefusedOfflineTokenMarksTheDeploymentAndFailsForGood() {
        deployedBy("user-1");
        when(keycloak.refresh("offline-1")).thenThrow(new KeycloakTokens.Rejected("invalid_grant"));

        assertThatThrownBy(() -> credentials.tokens(TENANT, FLOW))
                .isInstanceOfSatisfying(RunIdentityException.class, e -> assertThat(e.rejected()).isTrue());
        verify(flows).needsReauthorisation(FLOW, "invalid_grant");
    }

    @Test
    void anUnreachableKeycloakFailsTheAttemptOnly() {
        deployedBy("user-1");
        when(keycloak.refresh("offline-1")).thenThrow(new KeycloakTokens.Unavailable("down", null));

        assertThatThrownBy(() -> credentials.tokens(TENANT, FLOW))
                .isInstanceOfSatisfying(RunIdentityException.class, e -> assertThat(e.rejected()).isFalse());
        verify(flows, never()).needsReauthorisation(org.mockito.ArgumentMatchers.anyLong(), anyString());
    }

    @Test
    void aDeploymentAlreadyNeedingReauthorisationDoesNotAskKeycloak() {
        when(flows.identity(FLOW)).thenReturn(Optional.of(
                new IdentityState(Identity.deployer("user-1"), false, "invalid_grant")));

        assertThatThrownBy(() -> credentials.tokens(TENANT, FLOW))
                .isInstanceOfSatisfying(RunIdentityException.class, e -> assertThat(e.rejected()).isTrue());
        verifyNoInteractions(keycloak, offlineTokens);
    }

    @Test
    void aDeploymentWithoutAStoredTokenNeedsReauthorisation() {
        when(flows.identity(FLOW)).thenReturn(Optional.of(new IdentityState(Identity.deployer("u"), true, null)));
        when(offlineTokens.get(TENANT, FLOW)).thenReturn(null);

        assertThatThrownBy(() -> credentials.tokens(TENANT, FLOW)).isInstanceOf(RunIdentityException.class);
        verify(flows).needsReauthorisation(org.mockito.ArgumentMatchers.eq(FLOW), anyString());
    }

    @Test
    void aRotatedOfflineTokenIsKept() {
        deployedBy("user-1");
        when(keycloak.refresh("offline-1"))
                .thenReturn(new KeycloakTokens.Tokens("access-1", "offline-2", Duration.ofMinutes(5)));

        credentials.tokens(TENANT, FLOW);

        verify(offlineTokens).store(TENANT, FLOW, "offline-2");
    }

    @Test
    void theAccessTokenStandsForTheReissuedOfflineTokenToo() {
        deployedBy("user-1");
        when(keycloak.refresh("offline-1"))
                .thenReturn(new KeycloakTokens.Tokens("access-1", "offline-2", Duration.ofMinutes(5)));
        Supplier<String> first = credentials.tokens(TENANT, FLOW);

        // The next run reads the reissued token: no second refresh, and no second write to Vault.
        when(offlineTokens.get(TENANT, FLOW)).thenReturn("offline-2");
        Supplier<String> second = credentials.tokens(TENANT, FLOW);

        assertThat(first.get()).isEqualTo("access-1");
        assertThat(second.get()).isEqualTo("access-1");
        verify(keycloak, times(1)).refresh(anyString());
        verify(offlineTokens, times(1)).store(eq(TENANT), eq(FLOW), anyString());
    }

    @Test
    void aFreshDeploymentStartsFromItsOwnGrant() {
        deployedBy("user-1");
        when(keycloak.refresh("offline-1"))
                .thenReturn(new KeycloakTokens.Tokens("access-1", "offline-1", Duration.ofMinutes(5)));
        when(keycloak.refresh("offline-9"))
                .thenReturn(new KeycloakTokens.Tokens("access-9", "offline-9", Duration.ofMinutes(5)));
        credentials.tokens(TENANT, FLOW);

        when(offlineTokens.get(TENANT, FLOW)).thenReturn("offline-9");

        assertThat(credentials.tokens(TENANT, FLOW).get()).isEqualTo("access-9");
    }

    @Test
    void aRegisteredClientTakesItsOwnTokens() {
        when(flows.identity(FLOW)).thenReturn(Optional.of(new IdentityState(Identity.client("ingest"), true, null)));
        when(clients.tokens(TENANT, "ingest"))
                .thenReturn(new KeycloakTokens.Tokens("client-access", null, Duration.ofMinutes(5)));

        assertThat(credentials.tokens(TENANT, FLOW).get()).isEqualTo("client-access");
        verifyNoInteractions(offlineTokens);
    }
}
