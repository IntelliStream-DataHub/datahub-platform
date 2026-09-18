// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.repositories.node;

import ai.intellistream.datahub.jpa.domains.AssetEntity;
import ai.intellistream.datahub.jpa.domains.DatasetEntity;
import ai.intellistream.datahub.jpa.domains.FunctionEntity;
import ai.intellistream.datahub.models.datafilters.FunctionFilter;
import ai.intellistream.datahub.testsupport.SharedPostgres;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
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
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the function filter against a real PostgreSQL, because the two things it most has to
 * get right are invisible in the Java.
 *
 * <p>The first is the single-table discriminator. Every node type shares the {@code node} table, and
 * the Criteria API does not add the {@code node_type} restriction for these entities the way derived
 * and JPQL queries do — so a typed query that forgets it returns rows of every type, which the
 * transformer then presents as functions. {@code TimeseriesNodeTypeFilterIT} is the same guard for
 * timeseries; this is the one for functions, and the fixtures collide on name and data set
 * deliberately so a missing discriminator cannot pass.
 *
 * <p>The second is that the query exists at all: {@code FunctionCustomRepoImpl} is wired to
 * {@code FunctionRepository} by Spring Data's fragment naming convention, which nothing in the
 * compiler checks.
 *
 * <p>Run with {@code ./gradlew :datahub-infra:integrationTest} on a host with Docker/Podman.
 */
@Tag("integration")
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ContextConfiguration(classes = FunctionFilterIT.JpaConfig.class)
class FunctionFilterIT {

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        String url = SharedPostgres.newDatabase("function_filter_it");
        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.username", SharedPostgres::username);
        registry.add("spring.datasource.password", SharedPostgres::password);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        registry.add("spring.jpa.properties.hibernate.dialect", () -> "org.hibernate.dialect.PostgreSQLDialect");
    }

    @SpringBootConfiguration
    @EnableJpaRepositories(basePackageClasses = FunctionRepository.class)
    @EntityScan(basePackageClasses = FunctionEntity.class)
    static class JpaConfig {
    }

    @Autowired
    private FunctionRepository functionRepository;

    @PersistenceContext
    private EntityManager em;

    private Long datasetId;
    private Long otherDatasetId;

    @BeforeEach
    void seed() {
        DatasetEntity dataset = dataset("dataset_one", "Dataset One");
        DatasetEntity other = dataset("dataset_two", "Dataset Two");

        // A function and an asset colliding on name and sharing a data set, so any read that forgot
        // the node_type constraint would return both.
        FunctionEntity rollingAverage = new FunctionEntity();
        rollingAverage.setExternalId("fn_rolling_average");
        rollingAverage.setName("shared-name");
        rollingAverage.setDescription("rolling average over a window");
        rollingAverage.setLabels("FUNCTION");
        rollingAverage.setDataSet(dataset);
        em.persist(rollingAverage);

        AssetEntity asset = new AssetEntity();
        asset.setExternalId("asset_collides");
        asset.setName("shared-name");
        asset.setDescription("rolling average over a window");
        asset.setLabels("ASSET");
        asset.setDataSet(dataset);
        em.persist(asset);

        FunctionEntity elsewhere = new FunctionEntity();
        elsewhere.setExternalId("fn_elsewhere");
        elsewhere.setName("elsewhere");
        elsewhere.setLabels("FUNCTION");
        elsewhere.setDataSet(other);
        em.persist(elsewhere);

        em.flush();
        em.clear();
        datasetId = dataset.getId();
        otherDatasetId = other.getId();
    }

    private DatasetEntity dataset(String externalId, String name) {
        DatasetEntity ds = new DatasetEntity();
        ds.setExternalId(externalId);
        ds.setName(name);
        ds.setLabels("DATASET");
        em.persist(ds);
        return ds;
    }

    @Test
    @DisplayName("filter pins the FUNCTION discriminator despite a name-colliding asset")
    void filterReturnsOnlyFunctions() {
        List<FunctionEntity> result =
                functionRepository.filter(50, null, new FunctionFilter(), NodeSort.DEFAULT, null);

        assertThat(result).extracting(FunctionEntity::getExternalId)
                .containsExactlyInAnyOrder("fn_rolling_average", "fn_elsewhere");
    }

    @Test
    @DisplayName("a name criterion matches the function, never the asset that shares the name")
    void filterByNameExcludesOtherNodeTypes() {
        FunctionFilter criteria = new FunctionFilter();
        criteria.setName(List.of("shared-name"));

        List<FunctionEntity> result =
                functionRepository.filter(50, null, criteria, NodeSort.DEFAULT, null);

        assertThat(result).extracting(FunctionEntity::getExternalId)
                .containsExactly("fn_rolling_average");
    }

    @Test
    @DisplayName("the data set scope narrows to one data set")
    void filterNarrowsToTheGivenDataSets() {
        List<FunctionEntity> result = functionRepository.filter(
                50, Set.of(datasetId), new FunctionFilter(), NodeSort.DEFAULT, null);

        assertThat(result).extracting(FunctionEntity::getExternalId)
                .containsExactly("fn_rolling_average");
    }

    @Test
    @DisplayName("an empty data set scope returns nothing rather than everything")
    void filterWithAnEmptyScopeReturnsNothing() {
        // Dropping the predicate on an empty scope instead of short-circuiting is how "narrow to no
        // data sets" would silently become "every function in the tenant".
        assertThat(functionRepository.filter(50, Set.of(), new FunctionFilter(), NodeSort.DEFAULT, null))
                .isEmpty();
    }

    @Test
    @DisplayName("search matches the phrase and still returns only functions")
    void searchReturnsOnlyFunctions() {
        List<FunctionEntity> result =
                functionRepository.search("rolling", 50, null, new FunctionFilter());

        assertThat(result).extracting(FunctionEntity::getExternalId)
                .containsExactly("fn_rolling_average");
    }

    @Test
    @DisplayName("search ANDs its filter with the phrase rather than ignoring it")
    void searchAppliesTheFilterBesideThePhrase() {
        FunctionFilter criteria = new FunctionFilter();
        criteria.setExternalId(List.of("fn_elsewhere"));

        assertThat(functionRepository.search("rolling", 50, null, criteria)).isEmpty();
        assertThat(functionRepository.search("rolling", 50, Set.of(otherDatasetId), new FunctionFilter()))
                .isEmpty();
    }
}
