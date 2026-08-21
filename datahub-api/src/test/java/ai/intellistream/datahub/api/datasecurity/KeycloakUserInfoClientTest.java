// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.datasecurity;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Parsing of the UserInfo {@code organization} claim. The shapes below are pasted from a live
 * Keycloak 26.7 — see {@code datahub-api/KEYCLOAK_ORG_GROUPS.md}.
 */
class KeycloakUserInfoClientTest {

    private final KeycloakUserInfoClient client = new KeycloakUserInfoClient(
            JsonMapper.builder().build(),
            "http://keycloak:8090/realms/datahub",
            Duration.ofSeconds(3));

    @Test
    void parsesGroupsKeyedByOrganizationId() {
        String body = """
                {
                  "sub": "3e556d26-dbe0-4a57-ac23-a7a355122833",
                  "organization": {
                    "acme": {
                      "id": "f9cf24ba-6f97-46dd-8330-d205851d983d",
                      "groups": ["/datasets/data_set_sap/read", "/datasets/data_set_sap/write"]
                    }
                  },
                  "preferred_username": "dev"
                }
                """;

        Map<String, List<String>> groups = client.parseOrganizationGroups(body);

        assertThat(groups).containsOnlyKeys("f9cf24ba-6f97-46dd-8330-d205851d983d");
        assertThat(groups.get("f9cf24ba-6f97-46dd-8330-d205851d983d"))
                .containsExactly("/datasets/data_set_sap/read", "/datasets/data_set_sap/write");
    }

    @Test
    void parsesSeveralOrganizations() {
        String body = """
                {
                  "organization": {
                    "acme": { "id": "org-a", "groups": ["/datasets/a/read"] },
                    "beta": { "id": "org-b", "groups": ["/datasets/b/read"] }
                  }
                }
                """;

        assertThat(client.parseOrganizationGroups(body))
                .containsEntry("org-a", List.of("/datasets/a/read"))
                .containsEntry("org-b", List.of("/datasets/b/read"));
    }

    /** A member of an organization who holds no groups: present, but with nothing granted. */
    @Test
    void anOrganizationWithNoGroupsMapsToAnEmptyList() {
        String body = """
                { "organization": { "acme": { "id": "org-a" } } }
                """;

        assertThat(client.parseOrganizationGroups(body)).containsEntry("org-a", List.of());
    }

    /**
     * Without {@code addOrganizationId=true} on the membership mapper the claim carries no id, so
     * there is nothing to match TenantContext against. Skipped rather than guessed at.
     */
    @Test
    void skipsOrganizationsWithNoId() {
        String body = """
                { "organization": { "acme": { "groups": ["/datasets/a/read"] } } }
                """;

        assertThat(client.parseOrganizationGroups(body)).isEmpty();
    }

    /**
     * The stock mapper emits a flat array of aliases. It carries no ids and no groups, so it
     * yields nothing rather than being mistaken for a grant.
     */
    @Test
    void handlesTheFlatAliasArrayEmittedWithoutAddOrganizationId() {
        assertThat(client.parseOrganizationGroups("""
                { "organization": ["acme"] }
                """)).isEmpty();
    }

    /** A multi-organization user whose client did not request an organization scope. */
    @Test
    void handlesAnAbsentOrEmptyClaim() {
        assertThat(client.parseOrganizationGroups("""
                { "sub": "user-1" }
                """)).isEmpty();
        assertThat(client.parseOrganizationGroups("""
                { "organization": {} }
                """)).isEmpty();
    }

    @Test
    void rejectsAnUnparseableBody() {
        assertThatThrownBy(() -> client.parseOrganizationGroups("not json"))
                .isInstanceOf(UserInfoUnavailableException.class);
    }
}
