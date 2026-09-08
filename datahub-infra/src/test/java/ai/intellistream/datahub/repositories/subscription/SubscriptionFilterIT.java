// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.repositories.subscription;

import ai.intellistream.datahub.jpa.domains.DatasetEntity;
import ai.intellistream.datahub.jpa.domains.SubscriptionEntity;
import ai.intellistream.datahub.jpa.domains.TimeseriesEntity;
import ai.intellistream.datahub.models.IdCollection;
import ai.intellistream.datahub.models.datafilters.TimeFilter;
import ai.intellistream.datahub.models.paging.PageCursor;
import ai.intellistream.datahub.subscription.SubscriptionFilter;
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
import java.util.Collection;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises {@code POST /subscriptions/filter}'s query against a real PostgreSQL, because most of
 * what it has to get right is invisible in the Java: {@code ILIKE_FN} is a database function, the
 * timeseries criterion is an {@code EXISTS} whose whole job is not to duplicate rows the way the
 * {@code JOIN} it replaced did, and a keyset page is only correct if the {@code ORDER BY} and the
 * boundary comparison agree.
 *
 * <p>Run with {@code ./gradlew :datahub-infra:integrationTest} on a host with Docker/Podman.
 */
@Tag("integration")
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ContextConfiguration(classes = SubscriptionFilterIT.JpaConfig.class)
class SubscriptionFilterIT {

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        String url = SharedPostgres.newDatabase("subscription_filter_it");
        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.username", SharedPostgres::username);
        registry.add("spring.datasource.password", SharedPostgres::password);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        registry.add("spring.jpa.properties.hibernate.dialect", () -> "org.hibernate.dialect.PostgreSQLDialect");
    }

    @SpringBootConfiguration
    @EnableJpaRepositories(basePackageClasses = SubscriptionRepository.class)
    @EntityScan(basePackageClasses = SubscriptionEntity.class)
    static class JpaConfig {
    }

    @Autowired
    private SubscriptionRepository repository;

    @PersistenceContext
    private EntityManager em;

    // --- fixtures ------------------------------------------------------------------------------

    private TimeseriesEntity timeseries(String externalId) {
        return timeseries(externalId, null);
    }

    /** A timeseries in {@code dataSet}, or an orphan one when it is null. */
    private TimeseriesEntity timeseries(String externalId, DatasetEntity dataSet) {
        TimeseriesEntity ts = new TimeseriesEntity();
        ts.setExternalId(externalId);           // derives external_id_hash
        ts.setName(externalId);
        ts.setLabels("TIMESERIES");
        ts.setDataSet(dataSet);
        em.persist(ts);
        em.flush();
        return ts;
    }

    private DatasetEntity dataSet(String externalId) {
        DatasetEntity ds = new DatasetEntity();
        ds.setExternalId(externalId);           // derives external_id_hash
        ds.setName(externalId);
        ds.setLabels("DATASET");
        em.persist(ds);
        em.flush();
        return ds;
    }

    private SubscriptionEntity subscription(String externalId, String name, TimeseriesEntity... bound) {
        SubscriptionEntity sub = new SubscriptionEntity();
        sub.setExternalId(externalId);          // derives external_id_hash
        sub.setName(name);
        sub.setTimeseries(new java.util.LinkedHashSet<>(List.of(bound)));
        em.persist(sub);
        em.flush();
        return sub;
    }

    private SubscriptionEntity subscription(String externalId, TimeseriesEntity... bound) {
        return subscription(externalId, externalId, bound);
    }

    /** {@code date_created} is a @CreationTimestamp, so it can only be pinned after the insert. */
    private void createdAt(SubscriptionEntity sub, OffsetDateTime when) {
        em.createNativeQuery("UPDATE subscription SET date_created = :when WHERE id = :id")
                .setParameter("when", when)
                .setParameter("id", sub.getId())
                .executeUpdate();
        em.flush();
        em.clear();
    }

    private List<String> filter(SubscriptionFilter filter) {
        return filter(filter, 100, SubscriptionSort.DEFAULT, null);
    }

    private List<String> filter(SubscriptionFilter filter, int limit, SubscriptionSort sort, PageCursor cursor) {
        // null grants: the all-datasets reader. The narrowing case has its own tests below.
        return filter(filter, null, limit, sort, cursor);
    }

    private List<String> filter(SubscriptionFilter filter, Collection<Long> readableDataSetIds,
                                int limit, SubscriptionSort sort, PageCursor cursor) {
        em.flush();
        em.clear();
        return repository.filter(filter, readableDataSetIds, limit, sort, cursor).stream()
                .map(SubscriptionEntity::getExternalId)
                .toList();
    }

    // --- tests ---------------------------------------------------------------------------------

    @Test
    @DisplayName("An empty filter returns every user-managed subscription")
    void emptyFilterReturnsEverythingUserManaged() {
        TimeseriesEntity ts = timeseries("sensor_temp_room_a");
        subscription("fleet_dashboard", ts);
        subscription("plant_a_feed", ts);

        assertThat(filter(new SubscriptionFilter()))
                .containsExactlyInAnyOrder("fleet_dashboard", "plant_a_feed");
    }

    @Test
    @DisplayName("A null filter is treated as an empty one rather than throwing")
    void nullFilterIsTreatedAsEmpty() {
        TimeseriesEntity ts = timeseries("sensor_temp_room_a");
        subscription("fleet_dashboard", ts);

        assertThat(filter(null)).containsExactly("fleet_dashboard");
    }

    @Test
    @DisplayName("a trailing wildcard matches a prefix, case-insensitively")
    void externalIdPrefixMatchesCaseInsensitively() {
        TimeseriesEntity ts = timeseries("sensor_temp_room_a");
        subscription("plant_a_feed", ts);
        subscription("PLANT_A_alarms", ts);
        subscription("fleet_dashboard", ts);

        SubscriptionFilter f = new SubscriptionFilter();
        f.setExternalId(List.of("plant_a_*"));

        assertThat(filter(f)).containsExactlyInAnyOrder("plant_a_feed", "PLANT_A_alarms");
    }

    @Test
    @DisplayName("a literal entry and a wildcard entry OR together in one list")
    void externalIdMixesLiteralsAndPatterns() {
        TimeseriesEntity ts = timeseries("sensor_temp_room_a");
        subscription("fleet_dashboard", ts);
        subscription("plant_a_feed", ts);
        subscription("legacy_export", ts);

        SubscriptionFilter f = new SubscriptionFilter();
        f.setExternalId(List.of("fleet_dashboard", "plant_a_*"));

        assertThat(filter(f)).containsExactlyInAnyOrder("fleet_dashboard", "plant_a_feed");
    }

    @Test
    @DisplayName("an underscore in an external id is literal, not a single-character wildcard")
    void underscoreIsLiteral() {
        TimeseriesEntity ts = timeseries("sensor_temp_room_a");
        subscription("plant_a_feed", ts);
        subscription("plantXa_feed", ts);

        SubscriptionFilter f = new SubscriptionFilter();
        f.setExternalId(List.of("plant_a_feed"));

        assertThat(filter(f)).containsExactly("plant_a_feed");
    }

    @Test
    @DisplayName("name matches by pattern, case-insensitively")
    void nameMatchesByPattern() {
        TimeseriesEntity ts = timeseries("sensor_temp_room_a");
        subscription("one", "Fleet dashboard live feed", ts);
        subscription("two", "Boiler room readings", ts);

        SubscriptionFilter f = new SubscriptionFilter();
        f.setName(List.of("fleet*"));

        assertThat(filter(f)).containsExactly("one");
    }

    @Test
    @DisplayName("the timeseries criterion matches by id or external id, and never duplicates a row")
    void timeseriesCriterionMatchesEitherReferenceWithoutDuplicating() {
        TimeseriesEntity a = timeseries("sensor_temp_room_a");
        TimeseriesEntity b = timeseries("sensor_flow_main");
        TimeseriesEntity c = timeseries("sensor_unrelated");
        // Bound to both of the referenced timeseries: the JOIN this replaced returned it twice.
        subscription("fleet_dashboard", a, b);
        subscription("flow_only", b);
        subscription("unrelated", c);

        SubscriptionFilter f = new SubscriptionFilter();
        f.setTimeseries(List.of(
                IdCollection.createFromId(a.getId()),
                IdCollection.createFromExternalId("sensor_flow_main")));

        assertThat(filter(f)).containsExactlyInAnyOrder("fleet_dashboard", "flow_only");
    }

    @Test
    @DisplayName("criteria AND together")
    void criteriaAndTogether() {
        TimeseriesEntity a = timeseries("sensor_temp_room_a");
        TimeseriesEntity b = timeseries("sensor_flow_main");
        subscription("plant_a_feed", a);
        subscription("plant_a_alarms", b);

        SubscriptionFilter f = new SubscriptionFilter();
        f.setExternalId(List.of("plant_a_*"));
        f.setTimeseries(List.of(IdCollection.createFromId(a.getId())));

        assertThat(filter(f)).containsExactly("plant_a_feed");
    }

    @Test
    @DisplayName("createdTime min/max bound the range inclusively")
    void createdTimeRangeIsInclusive() {
        TimeseriesEntity ts = timeseries("sensor_temp_room_a");
        OffsetDateTime jan = OffsetDateTime.of(2026, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);
        OffsetDateTime feb = OffsetDateTime.of(2026, 2, 1, 0, 0, 0, 0, ZoneOffset.UTC);
        OffsetDateTime mar = OffsetDateTime.of(2026, 3, 1, 0, 0, 0, 0, ZoneOffset.UTC);
        createdAt(subscription("january", ts), jan);
        createdAt(subscription("february", ts), feb);
        createdAt(subscription("march", ts), mar);

        TimeFilter window = new TimeFilter();
        window.setMin(jan.toZonedDateTime());
        window.setMax(feb.toZonedDateTime());
        SubscriptionFilter f = new SubscriptionFilter();
        f.setCreatedTime(window);

        assertThat(filter(f)).containsExactlyInAnyOrder("january", "february");
    }

    @Test
    @DisplayName("an empty list places no restriction rather than matching nothing")
    void emptyListsPlaceNoRestriction() {
        TimeseriesEntity ts = timeseries("sensor_temp_room_a");
        subscription("fleet_dashboard", ts);

        SubscriptionFilter f = new SubscriptionFilter();
        f.setExternalId(List.of());
        f.setName(List.of());
        f.setTimeseries(List.of());

        assertThat(filter(f)).containsExactly("fleet_dashboard");
    }

    /**
     * The walk that {@code /list} could not do at all: every row reached exactly once, across pages,
     * with no {@code OFFSET}.
     */
    @Test
    @DisplayName("paging by cursor visits every row exactly once")
    void pagingByCursorVisitsEveryRowExactlyOnce() {
        TimeseriesEntity ts = timeseries("sensor_temp_room_a");
        OffsetDateTime base = OffsetDateTime.of(2026, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);
        for (int i = 0; i < 7; i++) {
            // Two share a created time, so the id tie-breaker is what makes the order total.
            createdAt(subscription("sub_" + i, ts), base.plusMinutes(i / 2));
        }

        List<String> seen = walk(SubscriptionSort.DEFAULT, 3);

        assertThat(seen).hasSize(7).doesNotHaveDuplicates()
                .containsExactlyInAnyOrder("sub_0", "sub_1", "sub_2", "sub_3", "sub_4", "sub_5", "sub_6");
    }

    @Test
    @DisplayName("paging by an ascending non-default sort visits every row exactly once")
    void pagingByExternalIdAscendingVisitsEveryRowExactlyOnce() {
        TimeseriesEntity ts = timeseries("sensor_temp_room_a");
        for (int i = 0; i < 5; i++) {
            subscription("sub_" + i, ts);
        }

        List<String> seen = walk(new SubscriptionSort("externalId", "externalId", false), 2);

        assertThat(seen).containsExactly("sub_0", "sub_1", "sub_2", "sub_3", "sub_4");
    }

    // --- dataset grants ------------------------------------------------------------------------

    @Test
    @DisplayName("a subscription is visible only when every timeseries it streams is granted")
    void aSubscriptionIsHiddenWhenAnyBoundTimeseriesIsOutsideTheGrants() {
        DatasetEntity granted = dataSet("data_set_granted");
        DatasetEntity ungranted = dataSet("data_set_ungranted");
        TimeseriesEntity readable = timeseries("sensor_temp_room_a", granted);
        TimeseriesEntity hidden = timeseries("sensor_temp_room_b", ungranted);

        subscription("all_granted", readable);
        subscription("partly_granted", readable, hidden);
        subscription("none_granted", hidden);

        // "Some readable member" would return partly_granted too — and it streams a timeseries the
        // caller could not have subscribed to, since create asserts read on every one of them.
        assertThat(filter(new SubscriptionFilter(), Set.of(granted.getId()), 100, SubscriptionSort.DEFAULT, null))
                .containsExactly("all_granted");
    }

    @Test
    @DisplayName("a subscription over a timeseries in no dataset is visible only to an all-datasets reader")
    void orphanTimeseriesAreVisibleOnlyToAnAllDatasetsReader() {
        DatasetEntity granted = dataSet("data_set_granted");
        subscription("over_orphan", timeseries("sensor_no_data_set"));
        subscription("over_granted", timeseries("sensor_temp_room_a", granted));

        // The LEFT join is what makes this case reachable: an inner one drops the orphan row, and
        // "no bound timeseries outside the grants" then reads as true for a subscription whose
        // every member is outside them.
        assertThat(filter(new SubscriptionFilter(), Set.of(granted.getId()), 100, SubscriptionSort.DEFAULT, null))
                .containsExactly("over_granted");

        assertThat(filter(new SubscriptionFilter()))
                .containsExactlyInAnyOrder("over_orphan", "over_granted");
    }

    @Test
    @DisplayName("the grant narrows the criteria rather than replacing them")
    void grantsAndCriteriaBothApply() {
        DatasetEntity granted = dataSet("data_set_granted");
        TimeseriesEntity readable = timeseries("sensor_temp_room_a", granted);
        subscription("fleet_dashboard", readable);
        subscription("plant_a_alarms", readable);

        SubscriptionFilter filter = new SubscriptionFilter();
        filter.setExternalId(List.of("plant_a_*"));

        assertThat(filter(filter, Set.of(granted.getId()), 100, SubscriptionSort.DEFAULT, null))
                .containsExactly("plant_a_alarms");
    }

    /** Page through everything, following nextCursor exactly as a client would. */
    private List<String> walk(SubscriptionSort sort, int pageSize) {
        List<String> seen = new ArrayList<>();
        PageCursor cursor = null;
        while (true) {
            em.flush();
            em.clear();
            List<SubscriptionEntity> page = repository.filter(new SubscriptionFilter(), null, pageSize, sort, cursor);
            page.forEach(s -> seen.add(s.getExternalId()));
            if (page.size() < pageSize) {
                return seen;
            }
            SubscriptionEntity last = page.get(page.size() - 1);
            cursor = new PageCursor(sort.property(), sort.descending(),
                    SubscriptionPredicateBuilder.cursorValue(last, sort), String.valueOf(last.getId()));
        }
    }
}
