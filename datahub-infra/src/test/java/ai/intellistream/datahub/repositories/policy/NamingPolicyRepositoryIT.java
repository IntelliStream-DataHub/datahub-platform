// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.repositories.policy;

import ai.intellistream.datahub.testsupport.SharedPostgres;
import ai.intellistream.datahub.helpers.text.ExternalIds;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurationPackage;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Which naming policies the resolver is given, against a real PostgreSQL.
 *
 * <p>All of this is decided by one SQL statement and none of it can be verified by reading it: a
 * {@code LEFT JOIN} that has to keep the unattached tenant policy, a discriminator filter, a
 * metadata {@code EXISTS} that separates naming policies from every other kind, and the
 * {@code is_deactivated} predicate that decides whether a policy governs at all. The evaluator
 * tests above stub this repository out, so what it actually returns is only covered here.
 */
@Tag("integration")
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ContextConfiguration(classes = NamingPolicyRepositoryIT.Config.class)
@Transactional
class NamingPolicyRepositoryIT {

    private static final long POLICY_NODE_TYPE = 6L;


    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        // The folded near-duplicate index is the thing under test, so it has to be the real one
        String url = SharedPostgres.newDatabase("naming_policy_it");
        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.username", SharedPostgres::username);
        registry.add("spring.datasource.password", SharedPostgres::password);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        registry.add("spring.jpa.properties.hibernate.dialect", () -> "org.hibernate.dialect.PostgreSQLDialect");
    }

    @SpringBootConfiguration
    @AutoConfigurationPackage
    static class Config {
        @Bean
        NamingPolicyRepository namingPolicyRepository() {
            return new NamingPolicyRepository();
        }
    }

    @Autowired
    private NamingPolicyRepository repository;

    @PersistenceContext
    private EntityManager entityManager;

    // --- fixtures ------------------------------------------------------------------------------

    /**
     * The relationship type the policy edges use. No migration seeds {@code relationship_type} —
     * edges name their type on demand at runtime — so the one this test traverses is inserted here.
     */
    @BeforeEach
    void seedRelationshipType() {
        entityManager.createNativeQuery(
                        "INSERT INTO relationship_type (name, hash, date_created, last_updated) "
                                + "SELECT 'ENFORCED_ON', :hash, now(), now() "
                                + "WHERE NOT EXISTS (SELECT 1 FROM relationship_type WHERE name = 'ENFORCED_ON')")
                .setParameter("hash", ExternalIds.hash("ENFORCED_ON"))
                .executeUpdate();
    }

    /** A policy node with the given metadata; returns its id. */
    private long policy(String externalId, boolean deactivated, Map<String, String> metadata) {
        entityManager.createNativeQuery(
                        "INSERT INTO node (external_id, external_id_hash, name, node_type, is_deactivated) "
                                + "VALUES (:ext, :hash, :name, :type, :off)")
                .setParameter("ext", externalId)
                .setParameter("hash", ExternalIds.hash(externalId))
                .setParameter("name", externalId)
                .setParameter("type", POLICY_NODE_TYPE)
                .setParameter("off", deactivated)
                .executeUpdate();
        long id = ((Number) entityManager
                .createNativeQuery("SELECT id FROM node WHERE external_id = :ext")
                .setParameter("ext", externalId)
                .getSingleResult()).longValue();
        metadata.forEach((k, v) -> entityManager.createNativeQuery(
                        "INSERT INTO node_metadata (node_id, key, value) VALUES (:id, :k, :v)")
                .setParameter("id", id).setParameter("k", k).setParameter("v", v)
                .executeUpdate());
        return id;
    }

    /** A non-policy node, to stand in for the data set a policy is attached to. */
    private long dataSet(String externalId) {
        entityManager.createNativeQuery(
                        "INSERT INTO node (external_id, external_id_hash, name, node_type) "
                                + "VALUES (:ext, :hash, :ext, 5)")
                .setParameter("ext", externalId)
                .setParameter("hash", ExternalIds.hash(externalId))
                .executeUpdate();
        return ((Number) entityManager
                .createNativeQuery("SELECT id FROM node WHERE external_id = :ext")
                .setParameter("ext", externalId)
                .getSingleResult()).longValue();
    }

    private void enforcedOn(long dataSetId, long policyId) {
        entityManager.createNativeQuery(
                        "INSERT INTO edge (relationship_type_id, rel_start, rel_end) "
                                + "SELECT rt.id, :ds, :p FROM relationship_type rt WHERE rt.name = 'ENFORCED_ON'")
                .setParameter("ds", dataSetId)
                .setParameter("p", policyId)
                .executeUpdate();
    }

    private static Map<String, String> namingMetadata(String preset) {
        return Map.of("kind", "naming", "preset", preset, "mode", "warn");
    }

    // --- tests ---------------------------------------------------------------------------------

    @Test
    @DisplayName("a tenant policy has no data set, and the LEFT JOIN must not drop it")
    void anUnattachedPolicyComesBackWithANullDataSetId() {
        policy("tenant_rule", false, namingMetadata("snake_case"));
        entityManager.flush();

        List<NamingPolicyRepository.NamingPolicyRow> rows = repository.findAll();

        assertThat(rows).singleElement().satisfies(row -> {
            assertThat(row.externalId()).isEqualTo("tenant_rule");
            assertThat(row.dataSetId()).isNull();
            assertThat(row.metadata()).containsEntry("preset", "snake_case");
        });
    }

    @Test
    @DisplayName("an attached policy carries the data set the ENFORCED_ON edge starts from")
    void anAttachedPolicyCarriesItsDataSetId() {
        long dataSetId = dataSet("plant_a");
        long policyId = policy("dataset_rule", false, namingMetadata("verbatim_tag"));
        enforcedOn(dataSetId, policyId);
        entityManager.flush();

        assertThat(repository.findAll()).singleElement().satisfies(row ->
                assertThat(row.dataSetId()).isEqualTo(dataSetId));
    }

    @Test
    @DisplayName("a deactivated policy is not returned, so it stops governing")
    void aDeactivatedPolicyIsExcluded() {
        policy("switched_off", true, namingMetadata("snake_case"));
        entityManager.flush();

        // Nothing comes back, which is what makes the resolver fall through to the shipped default.
        assertThat(repository.findAll()).isEmpty();
    }

    @Test
    @DisplayName("deactivating one policy leaves the others governing")
    void onlyTheDeactivatedPolicyIsExcluded() {
        policy("still_on", false, namingMetadata("snake_case"));
        policy("switched_off", true, namingMetadata("verbatim_tag"));
        entityManager.flush();

        assertThat(repository.findAll())
                .extracting(NamingPolicyRepository.NamingPolicyRow::externalId)
                .containsExactly("still_on");
    }

    @Test
    @DisplayName("reactivating a policy restores exactly the rule it was")
    void reactivatingRestoresTheSameConfiguration() {
        long id = policy("toggled", true, namingMetadata("snake_case"));
        entityManager.flush();
        assertThat(repository.findAll()).isEmpty();

        entityManager.createNativeQuery("UPDATE node SET is_deactivated = false WHERE id = :id")
                .setParameter("id", id).executeUpdate();
        entityManager.flush();

        // The configuration is untouched by the flag, so switching it back on cannot silently
        // change what the policy does.
        assertThat(repository.findAll()).singleElement().satisfies(row -> {
            assertThat(row.externalId()).isEqualTo("toggled");
            assertThat(row.metadata()).containsEntry("preset", "snake_case").containsEntry("mode", "warn");
        });
    }

    @Test
    @DisplayName("a policy node that is not a naming policy is ignored")
    void onlyNamingPoliciesAreReturned() {
        policy("retention_rule", false, Map.of("kind", "LIFECYCLE", "lifecycleAction", "DELETE"));
        entityManager.flush();

        assertThat(repository.findAll()).isEmpty();
    }

    @Test
    @DisplayName("a node that is not a policy is ignored even with naming metadata")
    void onlyPolicyNodesAreReturned() {
        long id = dataSet("looks_like_a_policy");
        entityManager.createNativeQuery(
                        "INSERT INTO node_metadata (node_id, key, value) VALUES (:id, 'kind', 'naming')")
                .setParameter("id", id).executeUpdate();
        entityManager.flush();

        assertThat(repository.findAll()).isEmpty();
    }
}
