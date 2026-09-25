// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.repositories.node;

import ai.intellistream.datahub.jpa.domains.AssetEntity;
import ai.intellistream.datahub.jpa.domains.FunctionEntity;
import ai.intellistream.datahub.jpa.domains.TimeseriesEntity;
import ai.intellistream.datahub.models.IdCollection;
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
 * {@code AssetService} and {@code FunctionService} resolve update and delete targets through their
 * typed repository, so that {@code /assets} and {@code /functions} writes cannot reach some other
 * node type. That holds only if the derived queries pin the single-table discriminator, which
 * nothing in the Java shows — so each repository is asked for the other types' rows, by id and by
 * external id, and must not return them.
 *
 * <p>Run with {@code ./gradlew :datahub-infra:integrationTest} on a host with Docker/Podman.
 */
@Tag("integration")
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ContextConfiguration(classes = TypedNodeRepositoryIT.JpaConfig.class)
class TypedNodeRepositoryIT {

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        String url = SharedPostgres.newDatabase("typed_node_repository_it");
        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.username", SharedPostgres::username);
        registry.add("spring.datasource.password", SharedPostgres::password);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        registry.add("spring.jpa.properties.hibernate.dialect", () -> "org.hibernate.dialect.PostgreSQLDialect");
    }

    @SpringBootConfiguration
    @EnableJpaRepositories(basePackageClasses = AssetRepository.class)
    @EntityScan(basePackageClasses = AssetEntity.class)
    static class JpaConfig {
    }

    @Autowired
    private AssetRepository assetRepository;

    @Autowired
    private FunctionRepository functionRepository;

    @PersistenceContext
    private EntityManager em;

    private Long assetId;
    private Long timeseriesId;
    private Long functionId;

    @BeforeEach
    void seed() {
        AssetEntity asset = new AssetEntity();
        asset.setExternalId("pump_1");
        asset.setName("Pump 1");
        asset.setLabels("ASSET");
        em.persist(asset);

        TimeseriesEntity timeseries = new TimeseriesEntity();
        timeseries.setExternalId("pump_1_pressure");
        timeseries.setName("Pump 1 pressure");
        timeseries.setLabels("TIMESERIES");
        em.persist(timeseries);

        FunctionEntity function = new FunctionEntity();
        function.setExternalId("fn_rolling_average");
        function.setName("Rolling average");
        function.setLabels("FUNCTION");
        em.persist(function);

        em.flush();
        em.clear();
        assetId = asset.getId();
        timeseriesId = timeseries.getId();
        functionId = function.getId();
    }

    @Test
    @DisplayName("update resolution: ids and external ids of other node types do not resolve")
    void findAllByIdOrExternalIdReturnsOnlyAssets() {
        List<AssetEntity> result = assetRepository.findAllByIdOrExternalId(
                Set.of(assetId, timeseriesId, functionId), Set.of("pump_1_pressure", "fn_rolling_average"));

        assertThat(result).extracting(AssetEntity::getId).containsExactly(assetId);
    }

    @Test
    @DisplayName("delete resolution: ids and external ids of other node types do not resolve")
    void findAllByIdCollectionReturnsOnlyAssets() {
        IdCollection timeseriesById = new IdCollection();
        timeseriesById.setId(timeseriesId);
        IdCollection timeseriesByExternalId = new IdCollection();
        timeseriesByExternalId.setExternalId("pump_1_pressure");
        IdCollection assetByExternalId = new IdCollection();
        assetByExternalId.setExternalId("pump_1");

        List<AssetEntity> result = assetRepository.findAllByIdCollection(
                List.of(timeseriesById, timeseriesByExternalId, assetByExternalId));

        assertThat(result).extracting(AssetEntity::getId).containsExactly(assetId);
    }

    @Test
    @DisplayName("function update resolution: ids and external ids of other node types do not resolve")
    void functionFindAllByIdOrExternalIdReturnsOnlyFunctions() {
        List<FunctionEntity> result = functionRepository.findAllByIdOrExternalId(
                // The function is reachable only through its differently-cased external id.
                Set.of(assetId), Set.of("pump_1", "FN_Rolling_Average"));

        assertThat(result).extracting(FunctionEntity::getId).containsExactly(functionId);
    }

    @Test
    @DisplayName("function delete resolution: ids and external ids of other node types do not resolve")
    void functionFindAllByIdCollectionReturnsOnlyFunctions() {
        IdCollection assetById = new IdCollection();
        assetById.setId(assetId);
        IdCollection timeseriesByExternalId = new IdCollection();
        timeseriesByExternalId.setExternalId("pump_1_pressure");
        IdCollection functionById = new IdCollection();
        functionById.setId(functionId);

        List<FunctionEntity> result = functionRepository.findAllByIdCollection(
                List.of(assetById, timeseriesByExternalId, functionById));

        assertThat(result).extracting(FunctionEntity::getId).containsExactly(functionId);
    }
}
