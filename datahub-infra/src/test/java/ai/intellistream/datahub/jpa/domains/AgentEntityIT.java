// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.jpa.domains;

import ai.intellistream.datahub.repositories.agent.AgentRepository;
import ai.intellistream.datahub.repositories.label.LabelRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
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
import ai.intellistream.datahub.testsupport.SharedPostgres;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link AgentEntity} against a real PostgreSQL carrying the real migrations.
 *
 * <p>Two things here can only be proven against Postgres. The first is the {@code text[]} tool
 * allowlist: {@code @JdbcTypeCode(SqlTypes.ARRAY)} over a {@code List<String>} either binds to a
 * genuine array column or it does not, and a unit test with a mocked repository cannot tell. The
 * second is {@code V43__agents.sql} itself — in particular that its seeded {@code console-assistant}
 * row lands, since that row is what makes an upgraded tenant answer its first request after the
 * migration exactly as it answered its last one before.
 */
@Tag("integration")
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ContextConfiguration(classes = AgentEntityIT.JpaConfig.class)
class AgentEntityIT {

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        String url = SharedPostgres.newDatabase("agent_it");
        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.username", SharedPostgres::username);
        registry.add("spring.datasource.password", SharedPostgres::password);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        registry.add("spring.jpa.properties.hibernate.dialect", () -> "org.hibernate.dialect.PostgreSQLDialect");
    }

    /**
     * {@code LabelRepository} only fixes the scan root that the sibling ITs already use;
     * {@code AgentRepository} has to be named too because repositories live in per-domain
     * sub-packages, not one flat package.
     */
    @SpringBootConfiguration
    @EnableJpaRepositories(basePackageClasses = {LabelRepository.class, AgentRepository.class})
    @EntityScan(basePackageClasses = Label.class)
    static class JpaConfig {
    }

    @PersistenceContext
    private EntityManager em;

    @Autowired
    private AgentRepository repository;

    @Test
    void theMigrationSeedsTheConsoleAssistantWithTheToolsItAlreadyHad() {
        AgentEntity seeded = repository.findByExternalId("console-assistant").orElseThrow();

        assertThat(seeded.getDisplayName()).isEqualTo("Console assistant");
        assertThat(seeded.isEnabled()).isTrue();
        // The exact set that was hardcoded in the console before this table existed. If this ever
        // shrinks, an upgrade silently takes tools away from every existing tenant.
        assertThat(seeded.getToolAllowlist()).containsExactlyInAnyOrder(
                "analysis_related_series", "dataset_list", "dataset_search", "edge_get",
                "edge_list_types", "event_filter", "event_get", "event_search", "label_list",
                "resource_fetch_nearest", "resource_fetch_related", "resource_get",
                "resource_search", "timeseries_fetch_datapoints", "timeseries_get",
                "timeseries_get_latest", "timeseries_list", "timeseries_search", "unit_get",
                "unit_list");
    }

    @Test
    void theSeededAgentLeavesEveryCostDialUnsetSoDeploymentDefaultsStillApply() {
        AgentEntity seeded = repository.findByExternalId("console-assistant").orElseThrow();

        assertThat(seeded.getBackendRef()).isNull();
        assertThat(seeded.getInstructions()).isNull();
        assertThat(seeded.getDefaultEffort()).isNull();
        assertThat(seeded.getMaxOutputTokens()).isNull();
        assertThat(seeded.getMaxIterations()).isNull();
    }

    @Test
    void theToolAllowlistRoundTripsThroughARealArrayColumn() {
        Long id = persist("analyst", List.of("event_search", "timeseries_get"));
        em.clear();

        AgentEntity reloaded = em.find(AgentEntity.class, id);
        assertThat(reloaded.getToolAllowlist()).containsExactly("event_search", "timeseries_get");

        // Genuinely text[], not a delimited string pretending to be one — otherwise a tool name
        // containing the delimiter would quietly become two tools.
        Object cardinality = em.createNativeQuery(
                        "SELECT cardinality(tool_allowlist) FROM agent WHERE id = :id")
                .setParameter("id", id)
                .getSingleResult();
        assertThat(((Number) cardinality).intValue()).isEqualTo(2);
    }

    @Test
    void anEmptyAllowlistPersistsAsEmptyRatherThanNull() {
        // The distinction the whole default-deny rule rests on: no tools must never read as
        // "unset", which a downstream reader could talk itself into treating as "all of them".
        Long id = persist("mute", List.of());
        em.clear();

        assertThat(em.find(AgentEntity.class, id).getToolAllowlist()).isEmpty();

        Object isNull = em.createNativeQuery(
                        "SELECT tool_allowlist IS NULL FROM agent WHERE id = :id")
                .setParameter("id", id)
                .getSingleResult();
        assertThat((Boolean) isNull).isFalse();
    }

    @Test
    void externalIdIsUniqueSoOneNameCannotMeanTwoAgents() {
        persist("duplicate", List.of("unit_list"));

        AgentEntity clash = new AgentEntity();
        clash.setExternalId("duplicate");
        clash.setDisplayName("Second");

        assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> {
            em.persist(clash);
            em.flush();
        })).isNotNull();
    }

    @Test
    void createdAtIsFilledByThePostgresDefaultRatherThanTheApplicationClock() {
        // Several api instances with drifting clocks must not disagree about when a row appeared.
        Long id = persist("stamped", List.of("unit_list"));
        em.clear();

        assertThat(em.find(AgentEntity.class, id).getCreatedAt()).isNotNull();
    }

    private Long persist(String externalId, List<String> tools) {
        AgentEntity agent = new AgentEntity();
        agent.setExternalId(externalId);
        agent.setDisplayName("Agent " + externalId);
        agent.setToolAllowlist(new ArrayList<>(tools));
        em.persist(agent);
        em.flush();
        return agent.getId();
    }
}
