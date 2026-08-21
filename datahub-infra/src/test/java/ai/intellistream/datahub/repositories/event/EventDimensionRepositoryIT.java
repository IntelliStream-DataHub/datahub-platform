// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.repositories.event;

import ai.intellistream.datahub.testsupport.SharedPostgres;
import ai.intellistream.datahub.repositories.event.EventDimensionRepository.Dimension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurationPackage;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the SQL behaviour of {@link EventDimensionRepository} against a real PostgreSQL container:
 * idempotent {@code ON CONFLICT} upserts, dataset-ACL scoping, case-insensitive substring search, and
 * the reconciliation rebuild that retracts stale values.
 *
 * <p>Runs non-transactionally ({@code NOT_SUPPORTED}) because the repository's upserts use
 * {@code REQUIRES_NEW} and must actually commit to be observable; {@link #clean()} resets state between
 * tests via {@code rebuild(empty)}. The positive cache is disabled (TTL 0) so every upsert reaches
 * Postgres and the {@code ON CONFLICT} path is what's under test, not the in-memory shortcut.
 */
@Tag("integration")
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ContextConfiguration(classes = EventDimensionRepositoryIT.Config.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class EventDimensionRepositoryIT {


    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        // The dimension tables come from Flyway V29/V30, applied here in full rather than
        String url = SharedPostgres.newDatabase("event_dim_it");
        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.username", SharedPostgres::username);
        registry.add("spring.datasource.password", SharedPostgres::password);
        // Schema comes from the migrations; Hibernate must not touch it.
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        registry.add("spring.jpa.properties.hibernate.dialect", () -> "org.hibernate.dialect.PostgreSQLDialect");
    }

    // @AutoConfigurationPackage registers this package as the auto-configuration base package, which
    // @DataJpaTest's repository auto-config and entity scanning both consult. The repository uses
    // native queries (no JPA entities) and isn't a Spring Data repository, so both scans find nothing
    // here — but without a base package they fail with "Unable to retrieve @EnableAutoConfiguration
    // base packages".
    @SpringBootConfiguration
    @AutoConfigurationPackage
    static class Config {
        @Bean
        EventDimensionRepository eventDimensionRepository() {
            // TTL 0 disables the positive cache so every upsert exercises ON CONFLICT.
            return new EventDimensionRepository(10_000, 0);
        }
    }

    @Autowired
    private EventDimensionRepository repository;

    @BeforeEach
    void clean() {
        for (Dimension d : Dimension.values()) {
            repository.rebuild(d, List.of());
        }
    }

    @Test
    @DisplayName("upsert then list returns sorted, de-duplicated distinct values")
    void upsertAndListDistinct() {
        repository.upsert(Dimension.TYPE, List.of(
                new DatasetValue(1, "warning"),
                new DatasetValue(1, "alarm"),
                new DatasetValue(2, "alarm")));

        assertThat(repository.listDistinct(Dimension.TYPE, null, null, 100))
                .containsExactly("alarm", "warning");
    }

    @Test
    @DisplayName("ON CONFLICT DO NOTHING makes repeated upserts idempotent")
    void upsertIsIdempotent() {
        repository.upsert(Dimension.TYPE, List.of(new DatasetValue(1, "alarm")));
        repository.upsert(Dimension.TYPE, List.of(new DatasetValue(1, "alarm"), new DatasetValue(1, "warning")));

        assertThat(repository.listDistinct(Dimension.TYPE, null, null, 100))
                .containsExactly("alarm", "warning");
    }

    @Test
    @DisplayName("listDistinct restricts to the readable dataset ids")
    void aclScoping() {
        repository.upsert(Dimension.TYPE, List.of(
                new DatasetValue(1, "alarm"),
                new DatasetValue(2, "warning")));

        assertThat(repository.listDistinct(Dimension.TYPE, Set.of(1L), null, 100)).containsExactly("alarm");
        assertThat(repository.listDistinct(Dimension.TYPE, Set.of(2L), null, 100)).containsExactly("warning");
        assertThat(repository.listDistinct(Dimension.TYPE, Set.of(1L, 2L), null, 100)).containsExactly("alarm", "warning");
        // Empty allow-set = nothing readable.
        assertThat(repository.listDistinct(Dimension.TYPE, Set.of(), null, 100)).isEmpty();
        // null = read everything.
        assertThat(repository.listDistinct(Dimension.TYPE, null, null, 100)).containsExactly("alarm", "warning");
    }

    @Test
    @DisplayName("substring search is case-insensitive and matches anywhere in the value")
    void substringSearch() {
        repository.upsert(Dimension.STATUS, List.of(
                new DatasetValue(1, "OPEN"),
                new DatasetValue(1, "reopened"),
                new DatasetValue(1, "closed")));

        assertThat(repository.listDistinct(Dimension.STATUS, null, "open", 100))
                .containsExactly("OPEN", "reopened");
        assertThat(repository.listDistinct(Dimension.STATUS, null, "CLOS", 100))
                .containsExactly("closed");
        assertThat(repository.listDistinct(Dimension.STATUS, null, "zzz", 100)).isEmpty();
    }

    @Test
    @DisplayName("a LIKE wildcard in the query is matched literally, not as a wildcard")
    void substringEscapesWildcards() {
        repository.upsert(Dimension.STATUS, List.of(
                new DatasetValue(1, "open"),
                new DatasetValue(1, "50%done")));

        // '%' must match a literal percent sign, not every row.
        assertThat(repository.listDistinct(Dimension.STATUS, null, "%", 100)).containsExactly("50%done");
    }

    @Test
    @DisplayName("rebuild replaces the table, retracting values no longer present")
    void rebuildRetractsStaleValues() {
        repository.upsert(Dimension.TYPE, List.of(
                new DatasetValue(1, "stale"),
                new DatasetValue(1, "keep")));

        repository.rebuild(Dimension.TYPE, List.of(new DatasetValue(1, "keep")));

        assertThat(repository.listDistinct(Dimension.TYPE, null, null, 100)).containsExactly("keep");
    }

    @Test
    @DisplayName("rebuildAll replaces all four tables in one statement, retracting stale values in each")
    void rebuildAllRetractsStaleValuesAcrossAllDimensions() {
        repository.upsert(Dimension.TYPE, List.of(new DatasetValue(1, "stale-type"), new DatasetValue(1, "keep-type")));
        repository.upsert(Dimension.SUB_TYPE, List.of(new DatasetValue(1, "stale-sub"), new DatasetValue(1, "keep-sub")));
        repository.upsert(Dimension.STATUS, List.of(new DatasetValue(1, "stale-status"), new DatasetValue(1, "keep-status")));
        repository.upsert(Dimension.SOURCE, List.of(new DatasetValue(1, "stale-source"), new DatasetValue(1, "keep-source")));

        repository.rebuildAll(Map.of(
                Dimension.TYPE, List.of(new DatasetValue(1, "keep-type")),
                Dimension.SUB_TYPE, List.of(new DatasetValue(1, "keep-sub")),
                Dimension.STATUS, List.of(new DatasetValue(1, "keep-status")),
                Dimension.SOURCE, List.of(new DatasetValue(1, "keep-source"))));

        assertThat(repository.listDistinct(Dimension.TYPE, null, null, 100)).containsExactly("keep-type");
        assertThat(repository.listDistinct(Dimension.SUB_TYPE, null, null, 100)).containsExactly("keep-sub");
        assertThat(repository.listDistinct(Dimension.STATUS, null, null, 100)).containsExactly("keep-status");
        assertThat(repository.listDistinct(Dimension.SOURCE, null, null, 100)).containsExactly("keep-source");
    }

    @Test
    @DisplayName("limit caps the number of values returned")
    void limitApplies() {
        repository.upsert(Dimension.TYPE, List.of(
                new DatasetValue(1, "a"),
                new DatasetValue(1, "b"),
                new DatasetValue(1, "c")));

        assertThat(repository.listDistinct(Dimension.TYPE, null, null, 2)).hasSize(2);
    }

    @Test
    @DisplayName("the SOURCE dimension upserts, lists and ACL-scopes through its own table")
    void sourceDimension() {
        repository.upsert(Dimension.SOURCE, List.of(
                new DatasetValue(1, "SAP"),
                new DatasetValue(2, "OSIsoft PI")));

        assertThat(repository.listDistinct(Dimension.SOURCE, null, null, 100))
                .containsExactly("OSIsoft PI", "SAP");
        assertThat(repository.listDistinct(Dimension.SOURCE, Set.of(1L), null, 100)).containsExactly("SAP");
        assertThat(repository.listDistinct(Dimension.SOURCE, null, "sap", 100)).containsExactly("SAP");
    }
}
