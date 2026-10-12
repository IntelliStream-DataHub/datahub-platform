// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.flow.identity;

import ai.intellistream.datahub.tenant.TenantConfigWriter;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.UnaryOperator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Deployers' offline tokens in the tenant's settings secret: stored and removed alongside everything
 * else in it, retried when someone else wrote the secret first, and revoked when replaced.
 */
class FlowTokensTest {

    private final KeycloakTokens keycloak = mock(KeycloakTokens.class);
    private final TenantConfigWriter writer = mock(TenantConfigWriter.class);
    private final Map<String, String> secret = new HashMap<>(Map.of("llm.api-key", "k"));
    private final FlowTokens tokens = new FlowTokens(id -> "foo", writer, keycloak);

    @SuppressWarnings("unchecked")
    private void vaultApplies() {
        when(writer.update(eq("foo"), any())).thenAnswer(call -> {
            Map<String, String> changed = ((UnaryOperator<Map<String, String>>) call.getArgument(1)).apply(secret);
            secret.clear();
            secret.putAll(changed);
            return changed;
        });
    }

    @Test
    void aNewTokenReplacesTheOldOneWhichIsRevoked() {
        vaultApplies();
        tokens.store("org-foo", 42, "offline-1");
        verify(keycloak, never()).revoke(anyString());

        tokens.store("org-foo", 42, "offline-2");

        assertThat(secret).containsEntry("flow.token.42", "offline-2").containsEntry("llm.api-key", "k");
        verify(keycloak).revoke("offline-1");
    }

    @Test
    void storingTheSameTokenAgainRevokesNothing() {
        secret.put("flow.token.42", "offline-1");
        vaultApplies();

        tokens.store("org-foo", 42, "offline-1");

        verify(keycloak, never()).revoke(anyString());
    }

    @Test
    void removingATokenRevokesIt() {
        secret.put("flow.token.42", "offline-1");
        vaultApplies();

        tokens.remove("org-foo", 42);

        assertThat(secret).doesNotContainKey("flow.token.42");
        verify(keycloak).revoke("offline-1");
    }

    @Test
    void aConflictingWriteIsRetriedAndAPersistentOneSurfaces() {
        AtomicInteger calls = new AtomicInteger();
        when(writer.update(eq("foo"), any())).thenAnswer(call -> {
            if (calls.incrementAndGet() == 1) {
                throw new IllegalStateException("someone else changed these settings first");
            }
            return Map.of();
        });
        tokens.store("org-foo", 42, "offline-1");
        assertThat(calls).hasValue(2);

        when(writer.update(eq("foo"), any())).thenThrow(new IllegalStateException("Vault refused"));
        assertThatThrownBy(() -> tokens.store("org-foo", 42, "offline-1"))
                .isInstanceOf(IllegalStateException.class);
    }
}
