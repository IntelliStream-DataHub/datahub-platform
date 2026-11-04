// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.flow.identity;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The service's own Keycloak client against a fake realm: what it sends for each grant, and how it
 * tells a refusal (the identity is no good) from Keycloak not working (try again later).
 */
class KeycloakTokensTest {

    private FakeKeycloak realm;
    private KeycloakTokens keycloak;

    @BeforeEach
    void setUp() throws Exception {
        realm = new FakeKeycloak();
        keycloak = new KeycloakTokens(realm.issuer(), "intellistream-flow", "s3cr&t",
                JsonMapper.builder().build(), HttpClient.newHttpClient());
    }

    @AfterEach
    void tearDown() {
        realm.close();
    }

    @Test
    void theAuthorizationUrlAsksForACodeWithTheGivenScopeAndState() {
        URI url = keycloak.authorizationUrl("http://localhost:8084/flows/deployment/callback",
                "openid offline_access organization:foo", "the-state");

        assertThat(url.toString()).startsWith(realm.issuer() + "/protocol/openid-connect/auth?")
                .contains("response_type=code", "client_id=intellistream-flow",
                        "redirect_uri=http%3A%2F%2Flocalhost%3A8084%2Fflows%2Fdeployment%2Fcallback",
                        "scope=openid+offline_access+organization%3Afoo", "state=the-state");
    }

    @Test
    void aCodeIsRedeemedAsTheConfidentialClient() {
        KeycloakTokens.Tokens tokens = keycloak.exchangeCode("the-code", "http://cb");

        assertThat(tokens).isEqualTo(new KeycloakTokens.Tokens("access-1", "offline-1", Duration.ofSeconds(300)));
        FakeKeycloak.Request request = realm.requests("/token").getFirst();
        assertThat(request.form()).containsEntry("grant_type", "authorization_code")
                .containsEntry("code", "the-code").containsEntry("redirect_uri", "http://cb");
        // RFC 6749 §2.3.1: the credentials are form-encoded before they are put in Basic.
        assertThat(new String(Base64.getDecoder().decode(request.authorization().substring(6)),
                StandardCharsets.UTF_8)).isEqualTo("intellistream-flow:s3cr%26t");
    }

    @Test
    void anOfflineTokenIsRefreshedAndAClientTakesItsOwnCredentials() {
        keycloak.refresh("offline-1");
        keycloak.clientCredentials("ingest-client", "ingest-secret", "openid organization:foo");

        assertThat(realm.requests("/token").get(0).form()).containsEntry("grant_type", "refresh_token")
                .containsEntry("refresh_token", "offline-1");
        FakeKeycloak.Request client = realm.requests("/token").get(1);
        assertThat(client.form()).containsEntry("grant_type", "client_credentials")
                .containsEntry("scope", "openid organization:foo");
        assertThat(new String(Base64.getDecoder().decode(client.authorization().substring(6)),
                StandardCharsets.UTF_8)).isEqualTo("ingest-client:ingest-secret");
    }

    @Test
    void aRefusedGrantIsARejectionAndAnythingElseIsUnavailability() {
        realm.tokenStatus = 400;
        realm.tokenBody = "{\"error\":\"invalid_grant\",\"error_description\":\"Offline user session not found\"}";
        assertThatThrownBy(() -> keycloak.refresh("revoked"))
                .isInstanceOf(KeycloakTokens.Rejected.class)
                .hasMessageContaining("invalid_grant").hasMessageContaining("Offline user session not found");

        realm.tokenStatus = 401;
        realm.tokenBody = "{\"error\":\"unauthorized_client\"}";
        assertThatThrownBy(() -> keycloak.clientCredentials("c", "wrong", "openid"))
                .isInstanceOf(KeycloakTokens.Rejected.class);

        realm.tokenStatus = 503;
        realm.tokenBody = "busy";
        assertThatThrownBy(() -> keycloak.refresh("offline-1"))
                .isInstanceOf(KeycloakTokens.Unavailable.class);
    }

    @Test
    void anUnreachableKeycloakIsUnavailability() {
        KeycloakTokens nowhere = new KeycloakTokens("http://127.0.0.1:1/realms/datahub", "c", "s",
                JsonMapper.builder().build(), HttpClient.newHttpClient());

        assertThatThrownBy(() -> nowhere.refresh("offline-1")).isInstanceOf(KeycloakTokens.Unavailable.class);
    }

    @Test
    void aReplacedTokenIsRevokedAsARefreshToken() {
        keycloak.revoke("old-offline");

        assertThat(realm.requests("/revoke")).singleElement().satisfies(r -> assertThat(r.form())
                .containsEntry("token", "old-offline").containsEntry("token_type_hint", "refresh_token"));
    }

    @Test
    void withoutAClientSecretNothingIsAsked() {
        KeycloakTokens unconfigured = new KeycloakTokens(realm.issuer(), "intellistream-flow", "",
                JsonMapper.builder().build(), HttpClient.newHttpClient());

        assertThatThrownBy(() -> unconfigured.refresh("offline-1"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("client-secret");
        assertThat(realm.requests("/token")).isEmpty();
    }
}
