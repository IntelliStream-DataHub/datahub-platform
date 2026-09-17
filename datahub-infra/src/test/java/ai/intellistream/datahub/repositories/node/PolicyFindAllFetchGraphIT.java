// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.repositories.node;

import ai.intellistream.datahub.helpers.text.ExternalIds;
import ai.intellistream.datahub.jpa.domains.NodeEntity;
import ai.intellistream.datahub.jpa.domains.PolicyEntity;
import ai.intellistream.datahub.models.Resource;
import ai.intellistream.datahub.testsupport.SharedPostgres;
import ai.intellistream.datahub.transformers.ResourceTransformer;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code GET /datasets/policies} reads the whole policy table and renders it outside the
 * transaction, so what {@code findAll} leaves lazy decides whether the endpoint answers or 500s.
 *
 * <p>{@code ResourceTransformer.from} copies {@code node.getMetadata()} — a LAZY
 * {@code @ElementCollection} — and {@code spring.jpa.open-in-view} is false, so by the time the
 * copy runs the session is gone and an uninitialised map throws {@code LazyInitializationException}.
 * The endpoint returned 200 only while the policy table was empty: with no rows there is no
 * collection to initialise, so the first policy anyone creates breaks the listing.
 *
 * <p>The {@code em.clear()} between the read and the transform is what makes that reachable from a
 * test — {@code @DataJpaTest} holds one session open for the whole method, which is the opposite of
 * what the endpoint does. Clearing detaches the entities the way the closing request transaction
 * does, so a lazy attribute fails here exactly as it fails in production.
 */
@Tag("integration")
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ContextConfiguration(classes = PolicyFindAllFetchGraphIT.JpaConfig.class)
class PolicyFindAllFetchGraphIT {

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        String url = SharedPostgres.newDatabase("policy_find_all_graph_it");
        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.username", SharedPostgres::username);
        registry.add("spring.datasource.password", SharedPostgres::password);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        registry.add("spring.jpa.properties.hibernate.dialect", () -> "org.hibernate.dialect.PostgreSQLDialect");
    }

    @SpringBootConfiguration
    @EnableJpaRepositories(basePackageClasses = PolicyRepository.class)
    @EntityScan(basePackageClasses = NodeEntity.class)
    static class JpaConfig {
    }

    @Autowired private PolicyRepository policyRepository;

    @PersistenceContext private EntityManager em;

    private PolicyEntity persistPolicy(String externalId, Map<String, String> metadata) {
        PolicyEntity policy = new PolicyEntity();
        policy.setExternalId(externalId);
        policy.setExternalIdHash(ExternalIds.hash(externalId));
        policy.setName(externalId);
        policy.setLabels("POLICY");
        metadata.forEach(policy::addToMetadata);
        PolicyEntity saved = policyRepository.saveAndFlush(policy);
        em.clear();
        return saved;
    }

    @Test
    @DisplayName("listing policies survives the transform after the session closes")
    void findAllFetchesMetadataSoTheDetachedTransformSucceeds() {
        persistPolicy("pytest_policy_naming", Map.of("severity", "warn"));

        List<PolicyEntity> nodes = policyRepository.findAll();
        em.clear();

        List<Resource> resources = ResourceTransformer.from(nodes);

        assertThat(resources)
                .as("a non-empty policy table is the case the missing graph broke")
                .singleElement()
                .extracting(Resource::getMetadata)
                .isEqualTo(Map.of("severity", "warn"));
    }

    @Test
    @DisplayName("a policy with no metadata reads back an empty map, not a lazy proxy")
    void findAllHandlesAPolicyWithoutMetadata() {
        persistPolicy("pytest_policy_bare", Map.of());

        List<PolicyEntity> nodes = policyRepository.findAll();
        em.clear();

        assertThat(ResourceTransformer.from(nodes))
                .singleElement()
                .extracting(Resource::getMetadata)
                .isEqualTo(Map.of());
    }

    @Test
    @DisplayName("every row in a multi-policy listing is transformable, not just the first")
    void findAllFetchesMetadataForEveryRow() {
        persistPolicy("pytest_policy_one", Map.of("scope", "global"));
        persistPolicy("pytest_policy_two", Map.of("scope", "dataset", "owner", "ops"));

        List<PolicyEntity> nodes = policyRepository.findAll();
        em.clear();

        assertThat(ResourceTransformer.from(nodes))
                .as("the join against node_metadata must not drop or duplicate rows")
                .hasSize(2)
                .extracting(Resource::getMetadata)
                .containsExactlyInAnyOrder(
                        Map.of("scope", "global"),
                        Map.of("scope", "dataset", "owner", "ops"));
    }
}
