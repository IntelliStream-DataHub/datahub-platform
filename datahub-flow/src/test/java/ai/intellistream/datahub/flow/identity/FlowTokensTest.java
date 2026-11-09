// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.flow.identity;

import ai.intellistream.datahub.tenant.TenantConfigWriter;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
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
 * else in it, retried when someone else wrote the secret first, and revoked when replaced — unless a
 * token still stored is the same person's, since revoking ends the offline session they share.
 */
class FlowTokensTest {

    private final KeycloakTokens keycloak = mock(KeycloakTokens.class);
    private final TenantConfigWriter writer = mock(TenantConfigWriter.class);
    private final Map<String, String> secret = new HashMap<>(Map.of("llm.api-key", "k"));
    private final FlowTokens tokens = new FlowTokens(id -> "foo", writer, keycloak);

    /** An offline token as Keycloak shapes one: a JWT, here unsigned, naming its person. */
    private static String offline(String person, int n) {
        Base64.Encoder b64 = Base64.getUrlEncoder().withoutPadding();
        String claims = "{\"sub\":\"" + person + "\",\"typ\":\"Offline\",\"jti\":\"" + n + "\"}";
        return b64.encodeToString("{\"alg\":\"HS512\"}".getBytes(StandardCharsets.UTF_8)) + "."
                + b64.encodeToString(claims.getBytes(StandardCharsets.UTF_8)) + ".sig";
    }

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
    void anotherPersonsReplacedTokenIsRevoked() {
        vaultApplies();
        tokens.store("org-foo", 42, offline("ann", 1));
        verify(keycloak, never()).revoke(anyString());

        tokens.store("org-foo", 42, offline("bob", 2));

        assertThat(secret).containsEntry("flow.token.42", offline("bob", 2)).containsEntry("llm.api-key", "k");
        verify(keycloak).revoke(offline("ann", 1));
    }

    @Test
    void theSamePersonsReplacedTokenIsKept() {
        // A refresh's reissued token, or a redeploy from the same sign-in: one offline session.
        secret.put("flow.token.42", offline("ann", 1));
        vaultApplies();

        tokens.store("org-foo", 42, offline("ann", 2));

        assertThat(secret).containsEntry("flow.token.42", offline("ann", 2));
        verify(keycloak, never()).revoke(anyString());
    }

    @Test
    void aReplacedTokenIsKeptWhileThePersonDeploysAnotherFlow() {
        secret.put("flow.token.42", offline("ann", 1));
        secret.put("flow.token.7", offline("ann", 3));
        vaultApplies();

        tokens.store("org-foo", 42, offline("bob", 2));
        tokens.remove("org-foo", 7);

        verify(keycloak, never()).revoke(offline("ann", 1));
        verify(keycloak).revoke(offline("ann", 3));
    }

    @Test
    void aTokenWhosePersonCannotBeReadIsNotRevoked() {
        secret.put("flow.token.42", "opaque");
        vaultApplies();

        tokens.remove("org-foo", 42);

        assertThat(secret).doesNotContainKey("flow.token.42");
        verify(keycloak, never()).revoke(anyString());
    }

    @Test
    void storingTheSameTokenAgainRevokesNothing() {
        secret.put("flow.token.42", offline("ann", 1));
        vaultApplies();

        tokens.store("org-foo", 42, offline("ann", 1));

        verify(keycloak, never()).revoke(anyString());
    }

    @Test
    void removingAPersonsLastTokenRevokesIt() {
        secret.put("flow.token.42", offline("ann", 1));
        vaultApplies();

        tokens.remove("org-foo", 42);

        assertThat(secret).doesNotContainKey("flow.token.42");
        verify(keycloak).revoke(offline("ann", 1));
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
