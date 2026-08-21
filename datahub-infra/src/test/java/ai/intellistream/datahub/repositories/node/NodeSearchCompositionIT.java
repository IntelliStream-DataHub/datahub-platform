// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.repositories.node;

import ai.intellistream.datahub.testsupport.SharedPostgres;
import ai.intellistream.datahub.jpa.domains.DatasetEntity;
import ai.intellistream.datahub.jpa.domains.TimeseriesEntity;
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

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The full-text phrase and the caller's {@code filter} compose into one query, and that query still
 * uses the GIN index.
 *
 * <p>Search used to be two queries: a native full-text query, then the filter re-asked about the
 * ids it returned. It is now one, with the phrase registered as
 * {@link FtsMatchFunctionContributor#FUNCTION_NAME} so it can sit beside the other predicates.
 *
 * <p>The index assertion is the point of running this against a real database. {@code node_fts_idx}
 * is an <em>expression</em> index, and Postgres only uses one when the query expression parses to
 * the same tree. Get a {@code coalesce} or the {@code 'simple'} configuration wrong and nothing
 * fails: the rows still come back, by sequential scan, on every search forever. Nothing in Java can
 * catch that.
 *
 * <p>The projection bug this class used to guard (a hand-maintained {@code ALL_NODE_FIELDS} column
 * list that threw {@code "The column name is_deactivated was not found in this ResultSet"} when a
 * {@code NOT NULL} column was added) can no longer happen: there is no hand-written SQL left here,
 * so Hibernate derives the projection. The mapping assertions below still run for free.
 *
 * <p>Runs against a real PostgreSQL container (Testcontainers), migrated with the production Flyway scripts.
 */
@Tag("integration")
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ContextConfiguration(classes = NodeSearchCompositionIT.JpaConfig.class)
class NodeSearchCompositionIT {

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        String url = SharedPostgres.newDatabase("node_search_composition_it");
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
    private Long strong;
    private Long weakA;
    private Long weakB;

    @BeforeEach
    void seed() {
        DatasetEntity dataset = new DatasetEntity();
        dataset.setExternalId("search_dataset");
        dataset.setName("Search Dataset");
        dataset.setLabels("test");
        em.persist(dataset);

        TimeseriesEntity timeseries = new TimeseriesEntity();
        timeseries.setExternalId("widgettelemetry_ts");
        timeseries.setName("widgettelemetry probe");
        timeseries.setDescription("hydraulic pressure sensor");
        timeseries.setLabels("test");
        timeseries.setDataSet(dataset);
        timeseries.setMetadata(new HashMap<>(Map.of("health", "good", "tier", "gold")));
        em.persist(timeseries);

        // Three rows carrying "pump" a different number of times. ts_rank with the default
        // normalisation scores on term frequency, so the order these come back in is a fact about
        // the data rather than about insertion order or the planner's mood.
        strong = persistPump(dataset, "pump_strong_ts", "pump pump station", "pump pump overhaul");
        weakA = persistPump(dataset, "pump_weak_a_ts", "inlet valve", "one pump mentioned once");
        weakB = persistPump(dataset, "pump_weak_b_ts", "outlet valve", "one pump mentioned once");

        // A tenant that does not name things in Latin script. Under the removed character
        // whitelist this row existed but was unreachable: no query that could match it was legal.
        persistPump(dataset, "cyrillic_ts", "температура датчик", "温度計");

        em.flush();
        em.clear();
        datasetId = dataset.getId();
    }

    private Long persistPump(DatasetEntity dataset, String externalId, String name, String description) {
        TimeseriesEntity e = new TimeseriesEntity();
        e.setExternalId(externalId);
        e.setName(name);
        e.setDescription(description);
        e.setLabels("test");
        e.setDataSet(dataset);
        em.persist(e);
        return e.getId();
    }

    @Test
    @DisplayName("the phrase matches on name, externalId and description, and maps cleanly")
    void phraseMatchesEveryIndexedColumn() {
        assertThat(timeseriesRepository.search("probe", 100, null, null))
                .extracting(TimeseriesEntity::getExternalId).containsExactly("widgettelemetry_ts");
        assertThat(timeseriesRepository.search("widgettelemetry_ts", 100, null, null))
                .extracting(TimeseriesEntity::getExternalId).containsExactly("widgettelemetry_ts");
        assertThat(timeseriesRepository.search("hydraulic", 100, null, null))
                .extracting(TimeseriesEntity::getExternalId).containsExactly("widgettelemetry_ts");
    }

    @Test
    @DisplayName("the last term is a prefix match, so `widget` finds `widgettelemetry`")
    void thePhraseIsAPrefixMatch() {
        assertThat(timeseriesRepository.search("widget", 100, null, null))
                .extracting(TimeseriesEntity::getExternalId).containsExactly("widgettelemetry_ts");
    }

    @Test
    @DisplayName("a filter criterion narrows the phrase's hits in the same query")
    void theFilterNarrowsThePhrase() {
        TimeseriesFilter matching = new TimeseriesFilter();
        matching.setName(List.of("widgettelemetry*"));
        assertThat(timeseriesRepository.search("hydraulic", 100, null, matching)).hasSize(1);

        // Same phrase, a filter the row does not satisfy: the phrase alone would have returned it.
        TimeseriesFilter excluding = new TimeseriesFilter();
        excluding.setName(List.of("something_else*"));
        assertThat(timeseriesRepository.search("hydraulic", 100, null, excluding)).isEmpty();
    }

    @Test
    @DisplayName("the data set scope narrows the phrase's hits in the same query")
    void theDataSetScopeNarrowsThePhrase() {
        assertThat(timeseriesRepository.search("hydraulic", 100, Set.of(datasetId), null)).hasSize(1);
        assertThat(timeseriesRepository.search("hydraulic", 100, Set.of(datasetId + 9999), null)).isEmpty();
    }

    @Test
    @DisplayName("limit caps the query itself, so it can stop early")
    void limitReachesTheQuery() {
        assertThat(timeseriesRepository.search("widgettelemetry", 1, null, null)).hasSize(1);
        assertThat(timeseriesRepository.search("widgettelemetry", 0 + 1, null, null)).hasSize(1);
    }

    @Test
    @DisplayName("results come back by relevance: the row carrying the term most often leads")
    void resultsAreOrderedByRelevance() {
        List<TimeseriesEntity> result = timeseriesRepository.search("pump", 100, null, null);

        assertThat(result).extracting(TimeseriesEntity::getId).startsWith(strong);
        assertThat(result).extracting(TimeseriesEntity::getExternalId)
                .containsExactly("pump_strong_ts", "pump_weak_a_ts", "pump_weak_b_ts");
    }

    /**
     * ts_rank ties constantly, so the tie-break is what actually makes a ranked search repeatable.
     * The two weak rows carry "pump" exactly once each and therefore score identically; without the
     * id term the database could return them either way round on each call, and a `limit` landing
     * inside that block would return a different row each time.
     */
    @Test
    @DisplayName("rows of equal rank are broken by id, so repeated searches agree")
    void equalRanksAreBrokenDeterministically() {
        assertThat(weakA).isLessThan(weakB);
        for (int i = 0; i < 3; i++) {
            assertThat(timeseriesRepository.search("mentioned", 100, null, null))
                    .extracting(TimeseriesEntity::getId)
                    .containsExactly(weakA, weakB);
        }
        // And the cut lands in the same place every time.
        assertThat(timeseriesRepository.search("mentioned", 1, null, null))
                .extracting(TimeseriesEntity::getId).containsExactly(weakA);
    }

    /**
     * Metadata criteria moved from one inner join per entry to one EXISTS per entry, which is what
     * let SELECT DISTINCT go and relevance ordering arrive. The join returned a node once per
     * matching metadata row; two entries matching would have yielded the node twice here.
     */
    @Test
    @DisplayName("several metadata entries AND together and still return the node once")
    void metadataCriteriaDoNotDuplicateRows() {
        TimeseriesFilter both = new TimeseriesFilter();
        both.setMetadata(Map.of("health", "good", "tier", "gold"));
        assertThat(timeseriesRepository.search("hydraulic", 100, null, both))
                .extracting(TimeseriesEntity::getExternalId)
                .containsExactly("widgettelemetry_ts");

        // A null value is "has this key, whatever it carries".
        TimeseriesFilter keyOnly = new TimeseriesFilter();
        Map<String, String> keyWithAnyValue = new HashMap<>();
        keyWithAnyValue.put("health", null); // Map.of rejects null values; the filter reads one as "any"
        keyOnly.setMetadata(keyWithAnyValue);
        assertThat(timeseriesRepository.search("hydraulic", 100, null, keyOnly)).hasSize(1);

        // Both entries must match, not either.
        TimeseriesFilter oneWrong = new TimeseriesFilter();
        oneWrong.setMetadata(Map.of("health", "good", "tier", "bronze"));
        assertThat(timeseriesRepository.search("hydraulic", 100, null, oneWrong)).isEmpty();
    }

    /**
     * The three things {@code SearchForm}'s character whitelist used to reject, all of which are
     * ordinary searches someone would actually type.
     *
     * <p>The externalId case is the sharpest: the search indexes {@code external_id} and the docs
     * advertise it, but {@code _} was not a permitted character, so no externalId following this
     * platform's own snake_case convention could be typed into it.
     */
    @Test
    @DisplayName("an externalId is searchable, underscores and all")
    void anExternalIdIsSearchable() {
        // The sharpest case the whitelist blocked: `_` was not a permitted character, so no
        // externalId following this platform's snake_case convention could be typed into a search
        // that indexes external_id and advertises it.
        assertThat(timeseriesRepository.search("widgettelemetry_ts", 100, null, null))
                .extracting(TimeseriesEntity::getExternalId).containsExactly("widgettelemetry_ts");
        assertThat(timeseriesRepository.search("pump_weak_a_ts", 100, null, null))
                .extracting(TimeseriesEntity::getExternalId).containsExactly("pump_weak_a_ts");
    }

    /**
     * Search-as-you-type, keystroke by keystroke. The trailing {@code :*} makes a prefix of the
     * last lexeme only, so the word being typed matches on its prefix while the words already
     * finished must match whole. Every intermediate state of typing has to find the row.
     *
     * <p>The externalId case only became possible when the character whitelist came off: {@code _}
     * was not permitted, so typing one into a search box earned a 400 partway through.
     */
    @Test
    @DisplayName("every prefix of what a user is typing finds the row")
    void typeAheadMatchesOnEveryKeystroke() {
        for (String typed : List.of("wid", "widget", "widgettele", "widgettelemetry")) {
            assertThat(timeseriesRepository.search(typed, 100, null, null))
                    .as("typing %s", typed)
                    .extracting(TimeseriesEntity::getExternalId).contains("widgettelemetry_ts");
        }
        // Mid-way through an externalId: the finished segments match whole, the last is a prefix.
        for (String typed : List.of("widgettelemetry_", "widgettelemetry_t", "widgettelemetry_ts")) {
            assertThat(timeseriesRepository.search(typed, 100, null, null))
                    .as("typing %s", typed)
                    .extracting(TimeseriesEntity::getExternalId).containsExactly("widgettelemetry_ts");
        }
        // And across words: "pump" is finished and must match whole, "sta" is still being typed.
        assertThat(timeseriesRepository.search("pump sta", 100, null, null))
                .extracting(TimeseriesEntity::getExternalId).containsExactly("pump_strong_ts");
        // Non-Latin gets the same treatment.
        assertThat(timeseriesRepository.search("температ", 100, null, null))
                .extracting(TimeseriesEntity::getExternalId).containsExactly("cyrillic_ts");
    }

    @Test
    @DisplayName("non-Latin scripts are searchable, exactly and by prefix")
    void nonLatinScriptsAreSearchable() {
        assertThat(timeseriesRepository.search("температура", 100, null, null))
                .extracting(TimeseriesEntity::getExternalId).containsExactly("cyrillic_ts");
        assertThat(timeseriesRepository.search("温度計", 100, null, null))
                .extracting(TimeseriesEntity::getExternalId).containsExactly("cyrillic_ts");
        // A run of CJK with no spaces is one token, so a shorter run only matches as a prefix —
        // which the trailing `:*` supplies.
        assertThat(timeseriesRepository.search("温度", 100, null, null))
                .extracting(TimeseriesEntity::getExternalId).containsExactly("cyrillic_ts");
    }

    /**
     * Hyphenated input is not simply "punctuation as separator": the parser emits the compound
     * lexeme <em>and</em> its parts, so the query demands a compound the unhyphenated document does
     * not contain. Pinned because it is surprising and easy to "fix" into something wrong.
     */
    @Test
    @DisplayName("a hyphenated phrase asks for the compound, so it does not match the spaced form")
    void aHyphenatedPhraseAsksForTheCompoundLexeme() {
        assertThat(timeseriesRepository.search("hydraulic pressure", 100, null, null))
                .extracting(TimeseriesEntity::getExternalId).containsExactly("widgettelemetry_ts");
        assertThat(timeseriesRepository.search("hydraulic-pressure", 100, null, null)).isEmpty();
    }

    /**
     * A phrase yielding no lexemes must find nothing, not fail.
     *
     * <p>This is the failure the removed whitelist was accidentally preventing: an empty tsquery
     * rendered as the empty string, and {@code '' || ':*'} is the literal {@code ':*'}, which
     * {@code to_tsquery} rejects with a syntax error. Every one of these was a 500 the moment the
     * character restriction came off, until the {@code nullif} guard went in.
     */
    @Test
    @DisplayName("a phrase with no lexemes finds nothing rather than erroring")
    void aDegeneratePhraseMatchesNothingWithoutFailing() {
        for (String phrase : List.of("---", "...", "!!!", "@@@", "   -   ")) {
            assertThat(timeseriesRepository.search(phrase, 100, null, null))
                    .as("phrase %s should match nothing, not throw", phrase)
                    .isEmpty();
        }
    }

    /**
     * A quoted span and a leading {@code -} are {@code websearch_to_tsquery} syntax that the
     * whitelist made unreachable, since neither character was permitted.
     */
    @Test
    @DisplayName("quoted phrases and negation reach the query parser")
    void websearchOperatorsAreReachable() {
        // Adjacent in that order: matches the row whose description is "hydraulic pressure sensor".
        assertThat(timeseriesRepository.search("\"hydraulic pressure\"", 100, null, null)).hasSize(1);
        // Not adjacent in that order: same two words, wrong sequence.
        assertThat(timeseriesRepository.search("\"pressure hydraulic\"", 100, null, null)).isEmpty();

        // "pump" excluding the strongly-matching row leaves the two weak ones.
        assertThat(timeseriesRepository.search("pump -overhaul", 100, null, null))
                .extracting(TimeseriesEntity::getExternalId)
                .containsExactly("pump_weak_a_ts", "pump_weak_b_ts");
    }

    /**
     * The expression the search emits must be the expression {@code node_fts_idx} was built on.
     *
     * <p>Seeded tables are too small for the planner to prefer any index, so sequential scans are
     * disabled for the duration of the EXPLAIN. That turns the question from "did the planner feel
     * like it?" into "could it use this index at all?", which is what we need to know.
     */
    @Test
    @DisplayName("the phrase expression matches node_fts_idx, so it is not a sequential scan")
    void theSearchExpressionCanUseTheGinIndex() {
        em.createNativeQuery("SET LOCAL enable_seqscan = off").executeUpdate();
        @SuppressWarnings("unchecked")
        List<Object> plan = em.createNativeQuery("""
                EXPLAIN SELECT * FROM node
                WHERE to_tsvector('simple', coalesce(node.name,'') || ' ' || coalesce(node.external_id,'') || ' ' || coalesce(node.description,''))
                      @@ to_tsquery('simple', cast(websearch_to_tsquery('simple', 'hydraulic') as text) || ':*')
                """).getResultList();
        String rendered = plan.stream().map(String::valueOf).reduce("", (a, b) -> a + "\n" + b);
        assertThat(rendered)
                .as("plan should reach node_fts_idx, not fall back to a scan:%s", rendered)
                .contains("node_fts_idx");
    }
}
