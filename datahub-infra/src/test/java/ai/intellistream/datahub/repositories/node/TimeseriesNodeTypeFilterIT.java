// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.repositories.node;

import ai.intellistream.datahub.testsupport.SharedPostgres;
import ai.intellistream.datahub.jpa.domains.AssetEntity;
import ai.intellistream.datahub.jpa.domains.DatasetEntity;
import ai.intellistream.datahub.jpa.domains.NodeEntity;
import ai.intellistream.datahub.jpa.domains.NodeType;
import ai.intellistream.datahub.jpa.domains.TimeseriesEntity;
import ai.intellistream.datahub.models.datafilters.ResourceFilter;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import ai.intellistream.datahub.jpa.domains.TimeseriesValueType;
import ai.intellistream.datahub.models.datafilters.TimeseriesFilter;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.Collection;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that timeseries reads never return rows of other node types.
 *
 * <p>All node types (asset, timeseries, function, resource, dataset, policy) share the single
 * {@code node} table via JPA single-table inheritance. Spring Data derived/JPQL queries get the
 * discriminator restriction automatically, but the CriteriaBuilder queries in
 * {@code TimeseriesCustomRepoImpl} ({@code list(...)}) do NOT, so those add an explicit node_type
 * filter. This test seeds a timeseries and an asset that deliberately collide on {@code name} and on
 * the same dataset, then asserts every read path (derived finder, both {@code list} overloads, and a
 * by-externalId lookup) returns only the timeseries. It is the regression guard for the
 * Criteria-discriminator gap.
 *
 * <p>Runs against a real PostgreSQL container (Testcontainers), migrated with the production Flyway scripts.
 */
@Tag("integration")
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ContextConfiguration(classes = TimeseriesNodeTypeFilterIT.JpaConfig.class)
class TimeseriesNodeTypeFilterIT {


    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        String url = SharedPostgres.newDatabase("timeseries_type_it");
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

    private Long datasetId;

    @BeforeEach
    void seed() {
        DatasetEntity dataset = new DatasetEntity();
        dataset.setExternalId("dataset_one");
        dataset.setName("Dataset One");
        dataset.setLabels("test");
        em.persist(dataset);

        // A timeseries and an asset that collide on name AND share the dataset, so any read that
        // forgot the node_type constraint would return both.
        TimeseriesEntity timeseries = new TimeseriesEntity();
        timeseries.setExternalId("shared_name_ts");
        timeseries.setName("shared-name");
        timeseries.setLabels("test");
        timeseries.setDataSet(dataset);
        em.persist(timeseries);

        AssetEntity asset = new AssetEntity();
        asset.setExternalId("shared_name_asset");
        asset.setName("shared-name");
        asset.setLabels("test");
        asset.setDataSet(dataset);
        em.persist(asset);

        em.flush();
        em.clear();
        datasetId = dataset.getId();
    }

    @Test
    @DisplayName("Derived finder returns only the timeseries despite a name-colliding asset")
    void findAllByNameExcludesOtherNodeTypes() {
        List<TimeseriesEntity> result = timeseriesRepository.findAllByName("shared-name");
        assertThat(result).extracting(TimeseriesEntity::getExternalId).containsExactly("shared_name_ts");
    }

    @Test
    @DisplayName("list(maxResults) returns only timeseries, not other recent nodes")
    void listReturnsOnlyTimeseries() {
        List<TimeseriesEntity> result = timeseriesRepository.list(50);
        assertThat(result).extracting(TimeseriesEntity::getExternalId).containsExactly("shared_name_ts");
    }

    @Test
    @DisplayName("list(maxResults, allowedDataSetIds) returns only timeseries within the dataset")
    void listByDatasetReturnsOnlyTimeseries() {
        List<TimeseriesEntity> result = timeseriesRepository.list(50, Set.of(datasetId));
        assertThat(result).extracting(TimeseriesEntity::getExternalId).containsExactly("shared_name_ts");
    }

    @Test
    @DisplayName("Lookup by externalId never resolves to a non-timeseries node")
    void findByExternalIdExcludesOtherNodeTypes() {
        assertThat(timeseriesRepository.findByExternalId("shared_name_asset")).isNull();
        assertThat(timeseriesRepository.findByExternalId("shared_name_ts")).isNotNull();
    }

    // --- the timeseries-specific filter criteria -------------------------------------------------
    // units and unitExternalIds became pattern lists (they were a single ILIKE and a single exact
    // match), and valueTypes is new: the value type was on the wire model and documented on create,
    // but nothing could filter by it.

    /** A timeseries in the seeded dataset with a unit and a value type. */
    private TimeseriesEntity timeseries(String externalId, String unit, String unitExternalId, String valueType) {
        TimeseriesValueType type = em.createQuery(
                        "select t from TimeseriesValueType t where lower(t.name) = :name", TimeseriesValueType.class)
                .setParameter("name", valueType.toLowerCase())
                .getSingleResult();

        TimeseriesEntity ts = new TimeseriesEntity();
        ts.setExternalId(externalId);
        ts.setName(externalId);
        ts.setLabels("test");
        ts.setDataSet(em.find(DatasetEntity.class, datasetId));
        ts.setUnit(unit);
        ts.setUnitExternalId(unitExternalId);
        ts.setValueType(type);
        em.persist(ts);
        em.flush();
        return ts;
    }

    private List<String> filter(TimeseriesFilter f) {
        return timeseriesRepository.filter(100, null, f).stream()
                .map(TimeseriesEntity::getExternalId)
                .toList();
    }

    @Test
    @DisplayName("units: a pattern list OR-s together, case-insensitively")
    void unitsArePatternList() {
        timeseries("flow_a", "kg/hr", "mass_flow_rate_kghr", "float");
        timeseries("temp_a", "DEG_C", "temperature_deg_c", "float");
        timeseries("count_a", "pcs", "count_pieces", "bigint");

        TimeseriesFilter f = new TimeseriesFilter();
        f.setUnit(List.of("kg/hr", "deg_*"));

        assertThat(filter(f)).containsExactlyInAnyOrder("flow_a", "temp_a");
    }

    @Test
    @DisplayName("unitExternalIds: several can be named at once, where one exact value used to be")
    void unitExternalIdsArePatternList() {
        timeseries("flow_b", "kg/hr", "mass_flow_rate_kghr", "float");
        timeseries("temp_b", "degC", "temperature_deg_c", "float");
        timeseries("count_b", "pcs", "count_pieces", "bigint");

        TimeseriesFilter f = new TimeseriesFilter();
        f.setUnitExternalId(List.of("mass_flow_rate_kghr", "temperature_*"));

        assertThat(filter(f)).containsExactlyInAnyOrder("flow_b", "temp_b");
    }

    @Test
    @DisplayName("valueTypes: matches the joined value type, case-insensitively")
    void valueTypesMatchExactly() {
        timeseries("num_1", "kg/hr", "mass_flow_rate_kghr", "float");
        timeseries("num_2", "pcs", "count_pieces", "bigint");
        timeseries("txt_1", null, null, "text");

        TimeseriesFilter f = new TimeseriesFilter();
        f.setValueType(List.of("FLOAT", "BigInt"));

        assertThat(filter(f)).containsExactlyInAnyOrder("num_1", "num_2");
    }

    @Test
    @DisplayName("valueTypes: an unknown type matches nothing rather than everything")
    void unknownValueTypeMatchesNothing() {
        timeseries("num_3", "kg/hr", "mass_flow_rate_kghr", "float");

        TimeseriesFilter f = new TimeseriesFilter();
        f.setValueType(List.of("NO_SUCH_TYPE"));

        assertThat(filter(f)).isEmpty();
    }

    @Test
    @DisplayName("the timeseries criteria AND with the inherited ones")
    void timeseriesCriteriaCombineWithInherited() {
        timeseries("rpm_pump_1", "rpm", "revolutions_per_minute", "float");
        timeseries("rpm_pump_2", "rpm", "revolutions_per_minute", "bigint");

        TimeseriesFilter f = new TimeseriesFilter();
        f.setExternalId(List.of("rpm_pump_*"));   // inherited from NodeFilter
        f.setUnit(List.of("rpm"));                // timeseries-specific
        f.setValueType(List.of("float"));

        assertThat(filter(f)).containsExactly("rpm_pump_1");
    }

    // --- the generic node query -------------------------------------------------------------------
    // /resources/filter spans every node type on purpose, while /datasets/filter and
    // /timeseries/filter each pin one. Both behaviours come from the same builder, so the seeded
    // name-colliding timeseries and asset check that the discriminator is applied exactly when it
    // was asked for — and not when it was not.

    private List<String> queryWithTypes(Collection<Long> nodeTypes) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<NodeEntity> q = cb.createQuery(NodeEntity.class);
        Root<NodeEntity> root = q.from(NodeEntity.class);

        ResourceFilter filter = new ResourceFilter();
        filter.setName(List.of("shared-name"));
        List<Predicate> predicates = NodePredicateBuilder.build(cb, q, root, filter, nodeTypes);

        q.select(root).distinct(true).where(predicates.toArray(new Predicate[0]))
                .orderBy(NodePredicateBuilder.orderBy(cb, root, NodeSort.DEFAULT));
        return em.createQuery(q).getResultList().stream().map(NodeEntity::getExternalId).toList();
    }

    @Test
    @DisplayName("no node types means every type — the generic query")
    void noNodeTypesSpansEveryType() {
        assertThat(queryWithTypes(List.of()))
                .containsExactlyInAnyOrder("shared_name_ts", "shared_name_asset");
    }

    @Test
    @DisplayName("one node type restricts to it, as the typed endpoints do")
    void oneNodeTypeRestricts() {
        assertThat(queryWithTypes(List.of(NodeType.TIMESERIES))).containsExactly("shared_name_ts");
        assertThat(queryWithTypes(List.of(NodeType.ASSET))).containsExactly("shared_name_asset");
    }

    @Test
    @DisplayName("several node types match any of them")
    void severalNodeTypesMatchAnyOfThem() {
        assertThat(queryWithTypes(List.of(NodeType.TIMESERIES, NodeType.ASSET)))
                .containsExactlyInAnyOrder("shared_name_ts", "shared_name_asset");
        assertThat(queryWithTypes(List.of(NodeType.TIMESERIES, NodeType.DATASET)))
                .containsExactly("shared_name_ts");
    }

    @Test
    @DisplayName("type names resolve to ids, and unknown ones drop out")
    void typeNamesResolveToIds() {
        assertThat(NodeType.idsForNames(List.of("timeseries", "ASSET", " dataset ")))
                .containsExactlyInAnyOrder(NodeType.TIMESERIES, NodeType.ASSET, NodeType.DATASET);
        // Unknown names contributing nothing is what lets the service tell "narrow to these types"
        // from "no restriction": an all-unknown list resolves to empty, and empty means no rows.
        assertThat(NodeType.idsForNames(List.of("nope"))).isEmpty();
        assertThat(NodeType.idsForNames(null)).isEmpty();
    }
}
