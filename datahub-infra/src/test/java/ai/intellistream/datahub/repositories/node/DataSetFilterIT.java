// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.repositories.node;

import ai.intellistream.datahub.jpa.domains.AssetEntity;
import ai.intellistream.datahub.jpa.domains.DatasetEntity;
import ai.intellistream.datahub.helpers.text.Labels;
import ai.intellistream.datahub.jpa.domains.Label;
import ai.intellistream.datahub.models.datafilters.TimeFilter;
import ai.intellistream.datahub.jpa.domains.NodeType;
import ai.intellistream.datahub.models.datafilters.DataSetFilter;
import ai.intellistream.datahub.models.datafilters.FilterPatterns;
import ai.intellistream.datahub.models.datafilters.TimeseriesFilter;
import ai.intellistream.datahub.models.paging.PageCursor;
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

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the dataset filter against a real PostgreSQL, because most of what it has to get right
 * is invisible in the Java: the single-table discriminator that Criteria queries do not add for
 * themselves, ILIKE semantics, and the difference between "the flag is false" and "the flag was
 * never set".
 *
 * <p>Run with {@code ./gradlew :datahub-infra:integrationTest} on a host with Docker/Podman.
 */
@Tag("integration")
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ContextConfiguration(classes = DataSetFilterIT.JpaConfig.class)
class DataSetFilterIT {

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        String url = SharedPostgres.newDatabase("dataset_filter_it");
        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.username", SharedPostgres::username);
        registry.add("spring.datasource.password", SharedPostgres::password);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        registry.add("spring.jpa.properties.hibernate.dialect", () -> "org.hibernate.dialect.PostgreSQLDialect");
    }

    @SpringBootConfiguration
    @EnableJpaRepositories(basePackageClasses = DataSetRepository.class)
    @EntityScan(basePackageClasses = DatasetEntity.class)
    static class JpaConfig {
    }

    @Autowired
    private DataSetRepository dataSetRepository;

    @PersistenceContext
    private EntityManager em;

    // --- fixtures ------------------------------------------------------------------------------

    private DatasetEntity dataset(String externalId, String name, String source) {
        DatasetEntity ds = dataset(externalId);
        ds.setName(name);
        ds.setSource(source);
        em.persist(ds);
        em.flush();
        return ds;
    }

    private DatasetEntity dataset(String externalId, Map<String, String> metadata) {
        DatasetEntity ds = new DatasetEntity();
        ds.setExternalId(externalId);           // derives external_id_hash
        ds.setName(externalId);
        ds.setLabels("DATASET");
        metadata.forEach(ds::addToMetadata);
        em.persist(ds);
        em.flush();
        return ds;
    }

    private DatasetEntity dataset(String externalId) {
        return dataset(externalId, Map.of());
    }

    /**
     * An asset sharing the datasets' external-id prefix. Present in nearly every case below as the
     * control for the discriminator: any filter that forgot {@code node_type} would return it.
     */
    private AssetEntity asset(String externalId) {
        AssetEntity a = new AssetEntity();
        a.setExternalId(externalId);
        a.setName(externalId);
        a.setLabels("ASSET");
        em.persist(a);
        em.flush();
        return a;
    }

    /** {@code date_created} is a @CreationTimestamp, so it can only be pinned after the insert. */
    private void createdAt(DatasetEntity ds, OffsetDateTime when) {
        em.createNativeQuery("UPDATE node SET date_created = :when WHERE id = :id")
                .setParameter("when", when)
                .setParameter("id", ds.getId())
                .executeUpdate();
        em.flush();
        em.clear();
    }

    private List<String> filter(DataSetFilter filter) {
        return filter(filter, 100);
    }

    private List<String> filter(DataSetFilter filter, int limit) {
        em.flush();
        em.clear();
        return dataSetRepository.filter(filter, limit).stream().map(DatasetEntity::getExternalId).toList();
    }

    // --- tests ---------------------------------------------------------------------------------

    @Test
    @DisplayName("An empty filter returns every dataset and nothing of another node type")
    void emptyFilterReturnsOnlyDatasets() {
        dataset("sap_work_orders");
        dataset("plant_a_telemetry");
        asset("sap_pump_01");

        assertThat(filter(new DataSetFilter()))
                .containsExactlyInAnyOrder("sap_work_orders", "plant_a_telemetry");
    }

    @Test
    @DisplayName("A null filter is treated as an empty one rather than throwing")
    void nullFilterIsTreatedAsEmpty() {
        dataset("sap_work_orders");

        assertThat(filter(null)).containsExactly("sap_work_orders");
    }

    @Test
    @DisplayName("a trailing wildcard matches a prefix, case-insensitively, datasets only")
    void externalIdPrefixMatchesCaseInsensitively() {
        dataset("sap_work_orders");
        dataset("SAP_invoices");
        dataset("plant_a_telemetry");
        asset("sap_pump_01");

        DataSetFilter f = new DataSetFilter();
        f.setExternalId(List.of("sap_*"));

        assertThat(filter(f)).containsExactlyInAnyOrder("sap_work_orders", "SAP_invoices");
    }

    @Test
    @DisplayName("a trailing wildcard anchors at the start — a mid-string match does not count")
    void externalIdPrefixIsAnchored() {
        dataset("sap_work_orders");
        dataset("legacy_sap_export");

        DataSetFilter f = new DataSetFilter();
        f.setExternalId(List.of("sap_*"));

        assertThat(filter(f)).containsExactly("sap_work_orders");
    }

    @Test
    @DisplayName("Several metadata entries AND together")
    void metadataEntriesAndTogether() {
        dataset("both", Map.of("owner", "plant-a", "tier", "gold"));
        dataset("owner_only", Map.of("owner", "plant-a"));
        dataset("tier_only", Map.of("tier", "gold"));
        dataset("wrong_value", Map.of("owner", "plant-b", "tier", "gold"));

        DataSetFilter f = new DataSetFilter();
        f.setMetadata(Map.of("owner", "plant-a", "tier", "gold"));

        assertThat(filter(f)).containsExactly("both");
    }

    @Test
    @DisplayName("createdTime min/max bound the range inclusively")
    void createdTimeRangeIsInclusive() {
        OffsetDateTime jan = OffsetDateTime.of(2026, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);
        createdAt(dataset("january"), jan);
        createdAt(dataset("february"), jan.plusMonths(1));
        createdAt(dataset("march"), jan.plusMonths(2));

        DataSetFilter f = new DataSetFilter();
        TimeFilter created = new TimeFilter();
        created.setMin(jan.plusMonths(1).toZonedDateTime());
        created.setMax(jan.plusMonths(2).toZonedDateTime());
        f.setCreatedTime(created);

        assertThat(filter(f)).containsExactlyInAnyOrder("february", "march");
    }

    @Test
    @DisplayName("Results come newest first and stop at the limit")
    void limitCapsNewestFirst() {
        OffsetDateTime jan = OffsetDateTime.of(2026, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);
        createdAt(dataset("oldest"), jan);
        createdAt(dataset("middle"), jan.plusMonths(1));
        createdAt(dataset("newest"), jan.plusMonths(2));

        assertThat(filter(new DataSetFilter(), 2)).containsExactly("newest", "middle");
    }

    @Test
    @DisplayName("A metadata filter never duplicates a dataset that matches several ways")
    void metadataFilterDoesNotDuplicateRows() {
        dataset("multi", Map.of("owner", "plant-a", "tier", "gold", "region", "north"));

        DataSetFilter f = new DataSetFilter();
        f.setMetadata(Map.of("owner", "plant-a", "tier", "gold"));

        assertThat(filter(f)).containsExactly("multi");
    }
    @Test
    @DisplayName("ids selects exactly the listed datasets")
    void idsSelectsTheListedDatasets() {
        DatasetEntity a = dataset("sap_work_orders");
        dataset("plant_a_telemetry");
        DatasetEntity c = dataset("legacy_export");

        DataSetFilter f = new DataSetFilter();
        f.setId(List.of(a.getId(), c.getId()));

        assertThat(filter(f)).containsExactlyInAnyOrder("sap_work_orders", "legacy_export");
    }

    @Test
    @DisplayName("externalIds matches through the hash, so case does not matter")
    void externalIdsMatchCaseInsensitively() {
        dataset("SAP_work_orders");
        dataset("plant_a_telemetry");
        asset("sap_work_orders_asset");

        DataSetFilter f = new DataSetFilter();
        f.setExternalId(List.of("sap_WORK_orders"));

        assertThat(filter(f)).containsExactly("SAP_work_orders");
    }

    @Test
    @DisplayName("names OR together and each entry is its own ILIKE pattern")
    void namesOrTogetherWithWildcards() {
        dataset("one", "SAP work orders", null);
        dataset("two", "SAP invoices", null);
        dataset("three", "Plant A", null);
        dataset("four", "Plant B", null);

        DataSetFilter f = new DataSetFilter();
        f.setName(List.of("sap%", "Plant A"));

        assertThat(filter(f)).containsExactlyInAnyOrder("one", "two", "three");
    }

    @Test
    @DisplayName("source matches case-insensitively, with wildcards")
    void sourceMatchesLikeResourceFilter() {
        dataset("one", "One", "SAP");
        dataset("two", "Two", "opcua");
        dataset("three", "Three", null);

        DataSetFilter f = new DataSetFilter();
        f.setSource(List.of("sap"));

        assertThat(filter(f)).containsExactly("one");
    }

    @Test
    @DisplayName("The new criteria AND with the existing ones rather than replacing them")
    void newCriteriaCombineWithTheOldOnes() {
        dataset("sap_work_orders", "SAP work orders", "sap").addToMetadata("owner", "plant-a");
        dataset("sap_invoices", "SAP invoices", "sap");
        em.flush();

        DataSetFilter f = new DataSetFilter();
        f.setName(List.of("SAP%"));
        f.setSource(List.of("sap"));
        f.setExternalId(List.of("sap_*"));
        f.setMetadata(Map.of("owner", "plant-a"));

        assertThat(filter(f)).containsExactly("sap_work_orders");
    }

    /**
     * An empty list is a caller who built a list and found nothing to put in it, not a caller
     * asking for nothing back — and an empty IN is not valid SQL either way.
     */
    @Test
    @DisplayName("Empty lists place no restriction rather than matching nothing")
    void emptyListsPlaceNoRestriction() {
        dataset("sap_work_orders");
        dataset("plant_a_telemetry");

        DataSetFilter f = new DataSetFilter();
        f.setId(List.of());
        f.setExternalId(List.of());
        f.setName(List.of());

        assertThat(filter(f)).containsExactlyInAnyOrder("sap_work_orders", "plant_a_telemetry");
    }

    // --- labels ---------------------------------------------------------------------------------
    // New with NodeFilter: no filter in the family could query labels before, even though every
    // node carries them. Worth exercising against a real database because the predicate is a
    // correlated counting subquery over the node_labels join table, and "carries all of these"
    // is exactly the semantics a naive join would get wrong.

    /**
     * Attaches labels to a dataset, creating each one the way the label API does — and reusing an
     * existing one by canonical name, since {@code label.hash} is globally unique.
     */
    private DatasetEntity datasetLabelled(String externalId, String... labelNames) {
        DatasetEntity ds = dataset(externalId);
        List<Label> attached = new ArrayList<>();
        for (String labelName : labelNames) {
            long hash = Labels.hash(labelName);
            Label label = em.createQuery("select l from Label l where l.hash = :hash", Label.class)
                    .setParameter("hash", hash)
                    .getResultStream().findFirst().orElse(null);
            if (label == null) {
                label = new Label();
                label.setName(labelName);   // canonicalises and derives the hash
                label.setColor("#ffffff");
                em.persist(label);
            }
            attached.add(label);
        }
        em.flush();
        ds.setLabelEntities(attached);
        em.merge(ds);
        em.flush();
        em.clear();
        return ds;
    }

    @Test
    @DisplayName("labels: matches datasets carrying the label")
    void labelsMatchesCarriers() {
        datasetLabelled("ds_pump", "PUMP");
        datasetLabelled("ds_valve", "VALVE");

        DataSetFilter filter = new DataSetFilter();
        filter.setLabels(List.of("PUMP"));

        assertThat(dataSetRepository.filter(filter, 100))
                .extracting(DatasetEntity::getExternalId)
                .containsExactly("ds_pump");
    }

    @Test
    @DisplayName("labels: several labels mean ALL of them, not any")
    void labelsRequireAllOfThem() {
        datasetLabelled("ds_both", "PUMP", "CRITICAL");
        datasetLabelled("ds_one", "PUMP");

        DataSetFilter filter = new DataSetFilter();
        filter.setLabels(List.of("PUMP", "CRITICAL"));

        // A join-per-label would return ds_one too if it were OR'd, and an unguarded single join
        // cannot express "has both" at all.
        assertThat(dataSetRepository.filter(filter, 100))
                .extracting(DatasetEntity::getExternalId)
                .containsExactly("ds_both");
    }

    @Test
    @DisplayName("labels: the caller's spelling is canonicalised before matching")
    void labelsAreCanonicalisedBeforeMatching() {
        datasetLabelled("ds_pump_a", "pump a");   // stored as PUMP_A

        DataSetFilter filter = new DataSetFilter();
        filter.setLabels(List.of("Pump-A"));      // a third spelling of the same label

        assertThat(dataSetRepository.filter(filter, 100))
                .extracting(DatasetEntity::getExternalId)
                .containsExactly("ds_pump_a");
    }

    @Test
    @DisplayName("labels: a label nothing carries matches nothing")
    void labelsThatMatchNoLabelMatchNoRows() {
        datasetLabelled("ds_pump", "PUMP");

        DataSetFilter filter = new DataSetFilter();
        filter.setLabels(List.of("NO_SUCH_LABEL"));

        assertThat(dataSetRepository.filter(filter, 100)).isEmpty();
    }

    @Test
    @DisplayName("labels: matching one label does not duplicate the row")
    void labelsDoNotMultiplyRows() {
        datasetLabelled("ds_many", "PUMP", "CRITICAL", "PLANT_A");

        DataSetFilter filter = new DataSetFilter();
        filter.setLabels(List.of("PUMP"));

        // The subquery keeps this one row; a join would have produced one per matching label.
        assertThat(dataSetRepository.filter(filter, 100)).hasSize(1);
    }


    // --- externalIds as a pattern list ----------------------------------------------------------
    // The wildcard half is what makes one field replace the old externalIds + externalIdPrefix
    // pair. Worth exercising against a real database: the escaping decides whether an underscore
    // is a wildcard, and Postgres is the only thing that can answer that honestly.

    @Test
    @DisplayName("externalIds: a list can mix exact ids with patterns, OR-ed together")
    void externalIdsMixExactAndPattern() {
        dataset("sap_work_orders");
        dataset("plant_a_telemetry");
        dataset("plant_b_telemetry");
        dataset("legacy_export");

        DataSetFilter f = new DataSetFilter();
        f.setExternalId(List.of("sap_work_orders", "plant_*"));

        assertThat(filter(f)).containsExactlyInAnyOrder(
                "sap_work_orders", "plant_a_telemetry", "plant_b_telemetry");
    }

    @Test
    @DisplayName("externalIds: * and % mean the same thing")
    void bothWildcardSpellingsAgree() {
        dataset("sap_work_orders");
        dataset("sap_invoices");

        DataSetFilter star = new DataSetFilter();
        star.setExternalId(List.of("sap_*"));
        DataSetFilter percent = new DataSetFilter();
        percent.setExternalId(List.of("sap_%"));

        assertThat(filter(star)).containsExactlyInAnyOrderElementsOf(filter(percent));
        assertThat(filter(star)).containsExactlyInAnyOrder("sap_work_orders", "sap_invoices");
    }

    @Test
    @DisplayName("externalIds: a leading wildcard matches a suffix")
    void leadingWildcardMatchesSuffix() {
        dataset("sap_archive");
        dataset("plant_archive");
        dataset("sap_live");

        DataSetFilter f = new DataSetFilter();
        f.setExternalId(List.of("*_archive"));

        assertThat(filter(f)).containsExactlyInAnyOrder("sap_archive", "plant_archive");
    }

    /**
     * The reason the escaping exists. Raw SQL LIKE reads {@code _} as "any single character", so
     * without escaping this filter would also return {@code sapXwork_orders}.
     */
    @Test
    @DisplayName("externalIds: an underscore is literal, not a single-character wildcard")
    void underscoreIsLiteral() {
        dataset("sap_work_orders");
        dataset("sapxwork_orders");

        DataSetFilter f = new DataSetFilter();
        f.setExternalId(List.of("sap_work_orders"));

        assertThat(filter(f)).containsExactly("sap_work_orders");
    }

    @Test
    @DisplayName("externalIds: an underscore stays literal inside a pattern too")
    void underscoreIsLiteralInsideAPattern() {
        dataset("sap_work_orders");
        dataset("sapxwork_orders");

        DataSetFilter f = new DataSetFilter();
        f.setExternalId(List.of("sap_work*"));

        assertThat(filter(f)).containsExactly("sap_work_orders");
    }

    @Test
    @DisplayName("sources: a pattern list OR-s together, case-insensitively")
    void sourcesArePatternList() {
        dataset("one", "One", "SAP");
        dataset("two", "Two", "opcua_north");
        dataset("three", "Three", "manual");

        DataSetFilter f = new DataSetFilter();
        f.setSource(List.of("sap", "opcua_*"));

        assertThat(filter(f)).containsExactlyInAnyOrder("one", "two");
    }

    // --- metadata key-only matching --------------------------------------------------------------
    // A null value means "has this key, whatever it carries" — the meaning EventFilter always gave
    // it, now shared. The old behaviour compared against SQL NULL, which is never true, so the same
    // body matched nothing and looked like an empty result rather than a bug. Worth an IT for
    // exactly that reason: only a real database can show which of the two it is doing.

    @Test
    @DisplayName("metadata: a null value matches the key whatever it carries")
    void nullMetadataValueMatchesTheKeyAlone() {
        dataset("healthy", Map.of("health", "good"));
        dataset("degraded", Map.of("health", "poor"));
        dataset("untracked", Map.of("owner", "plant-a"));

        DataSetFilter f = new DataSetFilter();
        Map<String, String> criteria = new HashMap<>();
        criteria.put("health", null);
        f.setMetadata(criteria);

        assertThat(filter(f)).containsExactlyInAnyOrder("healthy", "degraded");
    }

    @Test
    @DisplayName("metadata: a key-only entry ANDs with a key/value one")
    void keyOnlyAndsWithKeyValue() {
        dataset("both", Map.of("health", "good", "owner", "plant-a"));
        dataset("wrong_owner", Map.of("health", "good", "owner", "plant-b"));
        dataset("no_health", Map.of("owner", "plant-a"));

        DataSetFilter f = new DataSetFilter();
        Map<String, String> criteria = new HashMap<>();
        criteria.put("health", null);          // any health
        criteria.put("owner", "plant-a");      // but this owner
        f.setMetadata(criteria);

        assertThat(filter(f)).containsExactly("both");
    }

    @Test
    @DisplayName("metadata: a key nothing carries still matches nothing")
    void keyOnlyOnAnUnknownKeyMatchesNothing() {
        dataset("healthy", Map.of("health", "good"));

        DataSetFilter f = new DataSetFilter();
        Map<String, String> criteria = new HashMap<>();
        criteria.put("no_such_key", null);
        f.setMetadata(criteria);

        assertThat(filter(f)).isEmpty();
    }

    // --- sorting and keyset paging ---------------------------------------------------------------
    // The point of a cursor over OFFSET: each page is a range the index can seek to rather than
    // rows counted and discarded, and nothing written elsewhere shifts the walk. These check the
    // properties that matter — every row exactly once, in the requested order — on a set small
    // enough to verify exhaustively, which is the only way a boundary bug shows up.

    private List<String> filter(DataSetFilter f, NodeSort sort, PageCursor cursor, int limit) {
        return dataSetRepository.filter(f, limit, sort, cursor).stream()
                .map(DatasetEntity::getExternalId)
                .toList();
    }

    /** Walks the whole result set `pageSize` at a time and returns what it saw, in order. */
    private List<String> walk(DataSetFilter f, NodeSort sort, int pageSize) {
        List<String> seen = new ArrayList<>();
        PageCursor cursor = null;
        for (int guard = 0; guard < 50; guard++) {
            List<DatasetEntity> page = dataSetRepository.filter(f, pageSize, sort, cursor);
            page.forEach(d -> seen.add(d.getExternalId()));
            if (page.size() < pageSize) {
                break;
            }
            DatasetEntity last = page.get(page.size() - 1);
            cursor = new PageCursor(sort.property(), sort.descending(),
                    NodePredicateBuilder.cursorValue(last, sort), String.valueOf(last.getId()));
        }
        return seen;
    }

    private DataSetFilter named(String pattern) {
        DataSetFilter f = new DataSetFilter();
        f.setExternalId(List.of(pattern));
        return f;
    }

    @Test
    @DisplayName("sorting by a NOT NULL column, ascending and descending")
    void sortsByName() {
        dataset("sort_b", "Bravo", "src");
        dataset("sort_a", "Alpha", "src");
        dataset("sort_c", "Charlie", "src");

        NodeSort asc = new NodeSort("name", "name", false);
        NodeSort desc = new NodeSort("name", "name", true);

        assertThat(filter(named("sort_*"), asc, null, 100))
                .containsExactly("sort_a", "sort_b", "sort_c");
        assertThat(filter(named("sort_*"), desc, null, 100))
                .containsExactly("sort_c", "sort_b", "sort_a");
    }

    @Test
    @DisplayName("paging returns every row exactly once, in order")
    void pagingCoversEverythingExactlyOnce() {
        for (int i = 1; i <= 5; i++) {
            dataset("page_%d".formatted(i), "Page %d".formatted(i), "src");
        }
        NodeSort sort = new NodeSort("name", "name", false);

        List<String> single = filter(named("page_*"), sort, null, 100);
        List<String> paged = walk(named("page_*"), sort, 2);

        assertThat(single).hasSize(5);
        assertThat(paged).isEqualTo(single);
    }

    /** Ties are where keyset paging goes wrong without the id tie-breaker. */
    @Test
    @DisplayName("rows sharing a sort value are neither skipped nor repeated")
    void tiedSortValuesPageCorrectly() {
        for (int i = 1; i <= 4; i++) {
            dataset("tie_%d".formatted(i), "Same Name", "src");
        }
        NodeSort sort = new NodeSort("name", "name", false);

        List<String> single = filter(named("tie_*"), sort, null, 100);
        List<String> paged = walk(named("tie_*"), sort, 2);

        assertThat(single).hasSize(4);
        assertThat(paged).isEqualTo(single);
        assertThat(paged).doesNotHaveDuplicates();
    }

    /**
     * The nullable case, which is most of them under single-table inheritance. Nulls form their own
     * block — last ascending, first descending — and a cursor sitting inside it has only an id to
     * compare against. Getting this wrong drops the whole block silently.
     */
    @Test
    @DisplayName("a nullable sort column pages through its null block too")
    void pagingCrossesTheNullBlock() {
        dataset("nul_1", "One", "aaa");
        dataset("nul_2", "Two", null);
        dataset("nul_3", "Three", "bbb");
        dataset("nul_4", "Four", null);
        NodeSort asc = new NodeSort("source", "source", false);

        List<String> single = filter(named("nul_*"), asc, null, 100);
        List<String> paged = walk(named("nul_*"), asc, 1);

        assertThat(single).hasSize(4);
        assertThat(single).containsSubsequence("nul_1", "nul_3");   // non-nulls in order, nulls last
        assertThat(single.subList(2, 4)).containsExactlyInAnyOrder("nul_2", "nul_4");
        assertThat(paged).isEqualTo(single);
    }

    @Test
    @DisplayName("descending puts the null block first, and paging still covers everything")
    void nullBlockComesFirstDescending() {
        dataset("dnul_1", "One", "aaa");
        dataset("dnul_2", "Two", null);
        dataset("dnul_3", "Three", "bbb");
        NodeSort desc = new NodeSort("source", "source", true);

        List<String> single = filter(named("dnul_*"), desc, null, 100);
        List<String> paged = walk(named("dnul_*"), desc, 1);

        assertThat(single).hasSize(3);
        assertThat(single.get(0)).isEqualTo("dnul_2");   // the null sorts first descending
        assertThat(paged).isEqualTo(single);
    }

    // --- injection ---------------------------------------------------------------------------------
    // Pattern values reach the query through cb.literal(), and whether Hibernate binds or inlines a
    // literal is a configuration default rather than something this code states. These assert the
    // behaviour that matters either way: a payload is data, never syntax.

    @Test
    @DisplayName("a quote in a pattern is matched as text, not closed as a string")
    void quotesInPatternsAreData() {
        dataset("inj_plain", "Plain", "src");
        dataset("inj_quote", "O'Brien", "src");

        DataSetFilter f = new DataSetFilter();
        f.setName(List.of("O'Brien"));

        // If the value were inlined unescaped, this would be a syntax error rather than a match.
        assertThat(filter(f)).containsExactly("inj_quote");
    }

    @Test
    @DisplayName("a classic injection payload matches nothing instead of everything")
    void injectionPayloadIsNotExecuted() {
        dataset("inj_a", "Alpha", "src");
        dataset("inj_b", "Bravo", "src");

        for (String payload : List.of(
                "' OR 1=1 --",
                "'; DROP TABLE node; --",
                "%' OR '1'='1",
                "' UNION SELECT null,null,null --")) {
            DataSetFilter f = new DataSetFilter();
            f.setName(List.of(payload));

            // Nothing is named that, so nothing matches. Everything would mean the OR took effect;
            // an exception would mean the payload reached the parser.
            assertThat(filter(f)).as("payload %s", payload).isEmpty();
        }

        // And the table is still there.
        DataSetFilter after = new DataSetFilter();
        after.setExternalId(List.of("inj_*"));
        assertThat(filter(after)).containsExactlyInAnyOrder("inj_a", "inj_b");
    }

    @Test
    @DisplayName("payloads in every pattern field, and in metadata and labels")
    void injectionAcrossEveryCallerControlledField() {
        dataset("inj_c", Map.of("owner", "plant-a"));

        String payload = "' OR 1=1 --";
        DataSetFilter byExternalId = new DataSetFilter();
        byExternalId.setExternalId(List.of(payload));
        assertThat(filter(byExternalId)).isEmpty();

        DataSetFilter bySource = new DataSetFilter();
        bySource.setSource(List.of(payload));
        assertThat(filter(bySource)).isEmpty();

        DataSetFilter byLabel = new DataSetFilter();
        byLabel.setLabels(List.of(payload));
        assertThat(filter(byLabel)).isEmpty();

        DataSetFilter byMetadata = new DataSetFilter();
        byMetadata.setMetadata(Map.of(payload, payload));
        assertThat(filter(byMetadata)).isEmpty();
    }

    @Test
    @DisplayName("an unreadable cursor boundary is rejected, not silently restarted")
    void unreadableCursorBoundaryIsRejected() {
        // createdTime parses its boundary as epoch millis. Ignoring an unparseable one would hand a
        // paging client the first page forever; letting it reach Long.parseLong was a 500.
        NodeSort byCreated = new NodeSort("createdTime", "dateCreated", true);

        assertThat(byCreated.canReadBoundary("' OR 1=1 --")).isFalse();
        assertThat(byCreated.canReadBoundary("1745241600000")).isTrue();
        assertThat(byCreated.canReadBoundary(null)).as("null addresses the null block").isTrue();
        // A text column takes any boundary, payload or not — it is compared, never parsed.
        assertThat(new NodeSort("name", "name", false).canReadBoundary("' OR 1=1 --")).isTrue();
    }

    /**
     * A cursor is caller-supplied too, and its value becomes a comparison boundary.
     *
     * <p>The assertion is equivalence rather than emptiness: a payload used as a boundary is
     * <em>supposed</em> to match rows sorting after it, and {@code "' OR 1=1 --"} starts with a
     * quote, which sorts before any letter. So the row coming back is correct — it proves the
     * payload was compared as text. What would signal a problem is the payload behaving
     * differently from an ordinary string that sorts to the same place.
     */
    @Test
    @DisplayName("a payload inside a cursor is compared as text, like any other boundary")
    void injectionInsideACursorIsData() {
        dataset("inj_cur", "Cursor", "src");
        NodeSort sort = new NodeSort("name", "name", false);

        List<String> withPayload = filter(named("inj_cur*"), sort,
                new PageCursor("name", false, "' OR 1=1 --", "0"), 100);
        List<String> withOrdinaryBoundary = filter(named("inj_cur*"), sort,
                new PageCursor("name", false, "!benign", "0"), 100);

        assertThat(withPayload).isEqualTo(withOrdinaryBoundary).containsExactly("inj_cur");

        // And a boundary sorting after the row still excludes it — the comparison is real, not
        // short-circuited by anything the payload did.
        assertThat(filter(named("inj_cur*"), sort,
                new PageCursor("name", false, "zzz", "0"), 100)).isEmpty();
    }

    /**
     * The remaining caller-controlled string fields. names/externalIds/sources/labels/metadata are
     * covered above; these four take the same paths (ILIKE literal, or a lookup against a fixed
     * map) but were untested, which is the only reason to doubt them.
     */
    @Test
    @DisplayName("payloads in nodeTypes, valueTypes, units and unitExternalIds are data")
    void injectionInTheRemainingStringFields() {
        dataset("inj_rest", "Rest", "src");
        String payload = "' OR 1=1 --";

        // nodeTypes resolves through a fixed name->id map; an unknown name contributes nothing, so
        // an all-payload list narrows to no types rather than widening to all of them.
        assertThat(NodeType.idsForNames(List.of(payload))).isEmpty();

        // units/unitExternalIds/valueTypes live on the timeseries filter; the shared plumbing they
        // use is the same ILIKE-literal and bound-IN this filter exercises for names and labels.
        TimeseriesFilter ts = new TimeseriesFilter();
        ts.setUnit(List.of(payload));
        ts.setUnitExternalId(List.of(payload));
        ts.setValueType(List.of(payload));
        // A payload with no wildcard becomes a LIKE pattern matching exactly that literal string —
        // it is a value, never a fragment of the statement.
        assertThat(FilterPatterns.allPatterns(ts.getUnit())).containsExactly(payload);
        // And the characters SQL would otherwise read as wildcards are escaped, so an identifier
        // full of underscores matches itself rather than anything shaped like it.
        assertThat(FilterPatterns.allPatterns(List.of("' OR 1=1 --_x")).getFirst())
                .as("underscores are escaped even inside a payload")
                .isEqualTo("' OR 1=1 --\\_x");
    }
}
