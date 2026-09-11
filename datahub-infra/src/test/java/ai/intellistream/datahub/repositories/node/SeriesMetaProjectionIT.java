// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.repositories.node;

import ai.intellistream.datahub.jpa.domains.DatasetEntity;
import ai.intellistream.datahub.jpa.domains.TimeseriesEntity;
import ai.intellistream.datahub.jpa.domains.TimeseriesValueType;
import ai.intellistream.datahub.testsupport.SharedPostgres;
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
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The projection the binary ingest path authorises against, run against a real PostgreSQL. A unit
 * test cannot cover any of this: the query is a string, so its join shape and its mapping into the
 * record only exist once Hibernate has run it.
 *
 * <p>Run with {@code ./gradlew :datahub-infra:integrationTest} on a host with Podman.
 */
@Tag("integration")
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ContextConfiguration(classes = SeriesMetaProjectionIT.JpaConfig.class)
class SeriesMetaProjectionIT {

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        String url = SharedPostgres.newDatabase("series_meta_projection_it");
        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.username", SharedPostgres::username);
        registry.add("spring.datasource.password", SharedPostgres::password);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        registry.add("spring.jpa.properties.hibernate.dialect", () -> "org.hibernate.dialect.PostgreSQLDialect");
    }

    @SpringBootConfiguration
    @EnableJpaRepositories(basePackageClasses = TimeseriesRepository.class)
    @EntityScan(basePackageClasses = TimeseriesEntity.class)
    static class JpaConfig {
    }

    @Autowired
    private TimeseriesRepository timeseriesRepository;

    @PersistenceContext
    private EntityManager em;

    private long series(String externalId, int valueTypeId, DatasetEntity dataset) {
        TimeseriesEntity ts = new TimeseriesEntity();
        ts.setExternalId(externalId);
        ts.setName(externalId);
        ts.setLabels("TIMESERIES");
        ts.setValueType(new TimeseriesValueType(valueTypeId));
        ts.setDataSet(dataset);
        em.persist(ts);
        em.flush();
        return ts.getId();
    }

    @Test
    @DisplayName("Every series comes back with its dataset, value type and external id")
    void projectionCarriesWhatTheAclAndTypeCheckNeed() {
        DatasetEntity dataset = new DatasetEntity();
        dataset.setExternalId("plant_a");
        dataset.setName("Plant A");
        dataset.setLabels("DATASET");
        em.persist(dataset);
        em.flush();

        long floatId = series("pump_pressure", TimeseriesValueType.FLOAT32, dataset);
        long textId = series("pump_state", TimeseriesValueType.TEXT, dataset);

        Map<Long, SeriesMeta> found = timeseriesRepository.findSeriesMetaByIdIn(List.of(floatId, textId))
                .stream().collect(Collectors.toMap(SeriesMeta::id, Function.identity()));

        assertThat(found).hasSize(2);
        assertThat(found.get(floatId).externalId()).isEqualTo("pump_pressure");
        assertThat(found.get(floatId).valueTypeId()).isEqualTo(TimeseriesValueType.FLOAT32);
        assertThat(found.get(floatId).dataSetId()).isEqualTo(dataset.getId());
        assertThat(found.get(textId).valueTypeId()).isEqualTo(TimeseriesValueType.TEXT);
    }

    /**
     * The join has to be an outer one. An orphan series is the case the ACL treats most strictly,
     * so dropping it here would turn "you need an all-datasets grant" into a 404.
     */
    @Test
    @DisplayName("An orphan series is returned with a null dataset, not omitted")
    void orphanSeriesSurvivesTheJoin() {
        long orphanId = series("orphan_sensor", TimeseriesValueType.BIGINT, null);

        List<SeriesMeta> found = timeseriesRepository.findSeriesMetaByIdIn(List.of(orphanId));

        assertThat(found).singleElement().satisfies(meta -> {
            assertThat(meta.id()).isEqualTo(orphanId);
            assertThat(meta.externalId()).isEqualTo("orphan_sensor");
            assertThat(meta.valueTypeId()).isEqualTo(TimeseriesValueType.BIGINT);
            assertThat(meta.dataSetId()).isNull();
        });
    }

    @Test
    @DisplayName("An id that does not exist is simply absent, which is what becomes the 404")
    void unknownIdsAreAbsent() {
        long known = series("known_sensor", TimeseriesValueType.FLOAT, null);

        List<SeriesMeta> found = timeseriesRepository.findSeriesMetaByIdIn(List.of(known, 987_654_321L));

        assertThat(found).extracting(SeriesMeta::id).containsExactly(known);
    }
}
