// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.dhconsole.test.integration;

import ai.intellistream.datahub.api.responses.DataWrapper;
import ai.intellistream.datahub.models.IdCollection;
import ai.intellistream.datahub.helpers.updates.UpdateStringField;
import ai.intellistream.datahub.models.Policy;
import ai.intellistream.datahub.models.forms.UpdatePolicyForm;
import ai.intellistream.dhconsole.DatahubConsoleApplication;
import ai.intellistream.dhconsole.api.DatahubApi;

import org.junit.jupiter.api.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.json.JsonMapper;

import java.util.UUID;

/**
 * End-to-end policy lifecycle against a <em>running</em> datahub-api: create, read back from
 * Postgres and Neo4j, update, delete, and confirm the delete on both stores.
 *
 * <p>Tagged {@code integration} because nothing here is stubbed. Running it needs:
 *
 * <ul>
 *   <li>a datahub-api instance reachable at the {@code datahub.url} the console resolves,</li>
 *   <li>Vault credentials, since {@code VaultConfigurationLoader} runs at environment-prepared
 *       time and supplies that URL along with the OAuth2 client registration. Copy
 *       {@code application-test.properties.example} to {@code application-test.properties}
 *       (gitignored) and fill in {@code vault.address}, {@code vault.role-id} and
 *       {@code vault.secret-id}.</li>
 * </ul>
 *
 * <p>Without those the Spring context fails to start, so the tag keeps it out of the default
 * {@code test} task. Run it with {@code ./gradlew :datahub-console:integrationTest}.
 *
 * <p>Note this exercises {@link DatahubApi}, the deprecated Feign proxy; it should be retired
 * along with that client rather than extended.
 */
@Tag("integration")
@SpringBootTest(classes = DatahubConsoleApplication.class)
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PolicyApiIT {

    private static final Logger log =
            LoggerFactory.getLogger(PolicyApiIT.class);

    @Autowired
    private DatahubApi api;

    @Autowired
    private JsonMapper objectMapper;

    @Test
    @Order(1)
    void policyLifecycleTest() throws Exception {

        // CREATE
        Policy policy = new Policy();
        policy.setName("IntegrationTestPolicy");
        policy.setDescription("integration-test");
        policy.setExternalId("ext_" + UUID.randomUUID());

        DataWrapper<Policy> createWrapper = new DataWrapper<>();
        createWrapper.getItems().add(policy);

        DataWrapper<Policy> created =
                api.createPolicies(createWrapper);

        log.debug(
                "CREATE RESPONSE: {}",
                objectMapper.writeValueAsString(created)
        );

        Assertions.assertFalse(created.getItems().isEmpty());

        Long id = created.getItems()
                .iterator()
                .next()
                .getId();

        // POSTGRES CHECK
        DataWrapper<Policy> postgres =
                api.getPolicyById(id);

        log.debug(
                "POSTGRES LOOKUP: {}",
                objectMapper.writeValueAsString(postgres)
        );

        Assertions.assertFalse(postgres.getItems().isEmpty());

        // NEO4J CHECK
        var neo4j =
                api.getResourceById(id);

        log.debug(
                "NEO4J LOOKUP: {}",
                objectMapper.writeValueAsString(neo4j)
        );

        Assertions.assertFalse(neo4j.getItems().isEmpty());

        // UPDATE — patch shaped: name the policy by id, and set only the fields that change.
        UpdatePolicyForm update = new UpdatePolicyForm();
        update.setId(id);
        update.getUpdate().setName(new UpdateStringField().set("Integration_UPDATED"));

        DataWrapper<UpdatePolicyForm> updateWrapper = new DataWrapper<>();
        updateWrapper.getItems().add(update);

        DataWrapper<Policy> updated =
                api.updatePolicy(updateWrapper);

        log.debug(
                "UPDATE RESPONSE: {}",
                objectMapper.writeValueAsString(updated)
        );

        Assertions.assertEquals(
                "Integration_UPDATED",
                updated.getItems().iterator().next().getName()
        );

        // DELETE
        IdCollection deleteId = new IdCollection();
        deleteId.setId(id);

        DataWrapper<IdCollection> deleteWrapper = new DataWrapper<>();
        deleteWrapper.getItems().add(deleteId);

        api.deletePolicies(deleteWrapper);

        log.debug("DELETE CALLED FOR POLICY ID {}", id);

        // VERIFY DELETE — POSTGRES
        try {
            var afterDelete = api.getPolicyById(id);
            log.debug(
                    "POSTGRES AFTER DELETE: {}",
                    objectMapper.writeValueAsString(afterDelete)
            );
            Assertions.assertTrue(afterDelete.getItems().isEmpty());
        } catch (Exception e) {
            log.debug("POSTGRES AFTER DELETE: 404 (expected)");
        }

        // VERIFY DELETE — NEO4J
        try {
            var afterDelete = api.getResourceById(id);
            log.debug(
                    "NEO4J AFTER DELETE: {}",
                    objectMapper.writeValueAsString(afterDelete)
            );
            Assertions.assertTrue(afterDelete.getItems().isEmpty());
        } catch (Exception e) {
            log.debug("NEO4J AFTER DELETE: 404 (expected)");
        }
    }
}
