// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.flow.flows;

import ai.intellistream.datahub.flow.access.Caller.DataSets;
import ai.intellistream.datahub.flow.flows.FlowRepository.StoredFlow;
import ai.intellistream.datahub.testsupport.SharedPostgres;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The flow tables as a tenant gets them (V46, through the shared migrations), written and read by
 * {@link FlowRepository} in transactions as the service runs them.
 */
@Tag("integration")
class FlowRepositoryIT {

    private static FlowRepository repository;
    private static TransactionTemplate transaction;
    private static JdbcTemplate jdbc;

    @BeforeAll
    static void database() {
        DriverManagerDataSource ds = new DriverManagerDataSource(SharedPostgres.newDatabase("flow_repository_it"),
                SharedPostgres.username(), SharedPostgres.password());
        jdbc = new JdbcTemplate(ds);
        repository = new FlowRepository(jdbc);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(ds));
    }

    @Test
    void aFlowIsCreatedWithItsFirstVersionAndItsExternalIdIsUnique() {
        StoredFlow flow = create("create_once", 1);

        assertThat(flow.currentVersion()).isEqualTo(1);
        assertThat(flow.deployedVersion()).isNull();
        assertThat(flow.enabled()).isFalse();
        assertThat(repository.version(flow.id(), 1)).get()
                .satisfies(v -> assertThat(v.definition()).contains("\"externalId\": \"create_once\""));
        assertThatThrownBy(() -> create("create_once", 1)).isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void aChangeIsTheNextVersionAndAStaleOneIsRefused() {
        StoredFlow flow = create("versioned", 1);

        StoredFlow v2 = transaction.execute(s -> repository.addVersion(flow.id(), 1, 2, "ds_2", "renamed",
                definition("versioned"), "user-2")).orElseThrow();
        assertThat(v2.currentVersion()).isEqualTo(2);
        assertThat(v2.dataSetId()).isEqualTo(2);
        assertThat(v2.name()).isEqualTo("renamed");
        assertThat(v2.updatedBy()).isEqualTo("user-2");

        Optional<StoredFlow> late = transaction.execute(s -> repository.addVersion(flow.id(), 1, 2,
                "ds_2", "late", definition("versioned"), "user-3"));
        assertThat(late).isEmpty();
        assertThat(repository.versions(flow.id())).extracting(FlowRepository.StoredVersion::version)
                .containsExactly(2, 1);
        assertThat(repository.version(flow.id(), 3)).isEmpty();
    }

    @Test
    void aListHoldsOnlyFlowsInReadableDatasetsInPagesByExternalId() {
        create("list_a", 100);
        create("list_b", 200);
        create("list_c", 100);

        assertThat(repository.list(new DataSets(false, Set.of(100L)), false, "list_", 10))
                .extracting(StoredFlow::externalId).containsExactly("list_a", "list_c");
        assertThat(repository.list(new DataSets(false, Set.of()), false, "list_", 10)).isEmpty();
        assertThat(repository.list(DataSets.ALL, false, "list_a", 1))
                .extracting(StoredFlow::externalId).containsExactly("list_b");
    }

    @Test
    void anOrphanIsListedForThoseWhoSeeOrphansAndMovingItEndsThat() {
        StoredFlow orphan = create("orphan_a", 300);
        create("orphan_b", 400);
        repository.disable(orphan.id(), FlowRepository.DATASET_DELETED);

        DataSets none = new DataSets(false, Set.of(400L));
        assertThat(repository.list(none, false, "orphan_", 10)).extracting(StoredFlow::externalId)
                .containsExactly("orphan_b");
        assertThat(repository.list(none, true, "orphan_", 10)).extracting(StoredFlow::externalId)
                .containsExactly("orphan_a", "orphan_b");
        assertThat(repository.list(new DataSets(false, Set.of()), true, "orphan_", 10))
                .extracting(StoredFlow::externalId).containsExactly("orphan_a");
        assertThat(repository.find("orphan_a")).get().satisfies(f -> {
            assertThat(f.orphaned()).isTrue();
            assertThat(f.enabled()).isFalse();
        });

        // A save into the same, deleted dataset could not happen (it is not found); one into
        // another dataset moves it: no longer orphaned, still off.
        StoredFlow moved = transaction.execute(s -> repository.addVersion(orphan.id(), 1, 400, "ds_400",
                "moved", definition("orphan_a"), "admin-1")).orElseThrow();
        assertThat(moved.orphaned()).isFalse();
        assertThat(moved.disabledReason()).isNull();
        assertThat(moved.enabled()).isFalse();
        assertThat(moved.dataSetId()).isEqualTo(400);
    }

    @Test
    void deletingAFlowTakesItsVersionsWithIt() {
        StoredFlow flow = create("deleted", 500);

        transaction.executeWithoutResult(s -> repository.delete(flow.id()));

        assertThat(repository.find("deleted")).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM flow.flow_version WHERE flow_id = ?",
                Integer.class, flow.id())).isZero();
    }

    @Test
    void aCheckpointMovesAScheduleOnNeverBackAndOnlyForTheDeploymentItWasTakenFrom() {
        StoredFlow flow = create("scheduled", 600);
        Instant first = repository.now().minusSeconds(60);
        transaction.executeWithoutResult(s -> repository.deploy(flow.id(), 1, true, "{\"type\":\"schedule\"}", 3, 2,
                Duration.ofMinutes(5), FlowRepository.Identity.deployer("user-1"), "user-1", first, first));
        Instant deployedAt = repository.deployment(flow.id()).orElseThrow().deployedAt();
        Instant later = first.plusSeconds(30);

        repository.checkpointSchedules(List.of(
                new FlowRepository.ScheduleCheckpoint(flow.id(), deployedAt, later, later.plusSeconds(5), first)));
        assertThat(repository.deployment(flow.id()).orElseThrow()).satisfies(d -> {
            assertThat(d.nextTick()).isEqualTo(later);
            assertThat(d.nextDueAt()).isEqualTo(later.plusSeconds(5));
            assertThat(d.lastTick()).isEqualTo(first);
        });

        repository.checkpointSchedules(List.of(
                new FlowRepository.ScheduleCheckpoint(flow.id(), deployedAt, first, first, null)));
        assertThat(repository.deployment(flow.id()).orElseThrow().nextTick()).as("never back").isEqualTo(later);
        assertThat(repository.deployment(flow.id()).orElseThrow().lastTick()).as("no tick keeps the last").isEqualTo(first);

        repository.checkpointSchedules(List.of(new FlowRepository.ScheduleCheckpoint(flow.id(),
                deployedAt.plusSeconds(1), later.plusSeconds(60), later.plusSeconds(60), later)));
        assertThat(repository.deployment(flow.id()).orElseThrow().nextTick())
                .as("another deployment's checkpoint is not this one's").isEqualTo(later);

        repository.checkpointSchedules(List.of(
                new FlowRepository.ScheduleCheckpoint(flow.id(), deployedAt, null, null, later)));
        assertThat(repository.deployment(flow.id()).orElseThrow()).satisfies(d -> {
            assertThat(d.nextTick()).as("a schedule with no tick left").isNull();
            assertThat(d.nextDueAt()).isNull();
            assertThat(d.lastTick()).isEqualTo(later);
        });
    }

    @Test
    void aDeployedFlowCarriesItsWholeScheduleForTheRunStore() {
        StoredFlow flow = create("carried", 600);
        Instant first = repository.now().plusSeconds(60);
        transaction.executeWithoutResult(s -> repository.deploy(flow.id(), 1, true, "{\"type\":\"schedule\"}", 3, 2,
                Duration.ofMinutes(5), FlowRepository.Identity.deployer("user-1"), "user-1", first, first));

        assertThat(repository.deployedFlow(flow.id()).orElseThrow()).satisfies(f -> {
            assertThat(f.externalId()).isEqualTo("carried");
            assertThat(f.version()).isEqualTo(1);
            assertThat(f.trigger()).contains("schedule");
            assertThat(f.nextTick()).isEqualTo(first);
            assertThat(f.maxAttempts()).isEqualTo(2);
            assertThat(f.timeout()).isEqualTo(Duration.ofMinutes(5));
            assertThat(f.runAs()).isEqualTo("user-1");
            assertThat(f.identityOk()).isTrue();
            assertThat(f.deployedAt()).isEqualTo(repository.deployment(flow.id()).orElseThrow().deployedAt());
        });
        repository.needsReauthorisation(flow.id(), "invalid_grant");
        assertThat(repository.deployedFlow(flow.id()).orElseThrow().identityOk()).isFalse();
    }

    @Test
    void whatDispatchAndTheOverviewNeedOfDeployedFlowsComesFromTheFlowRows() {
        StoredFlow deployed = create("board_deployed", 700);
        create("board_never_deployed", 700);
        StoredFlow elsewhere = create("board_elsewhere", 701);
        for (StoredFlow f : List.of(deployed, elsewhere)) {
            transaction.executeWithoutResult(s -> repository.deploy(f.id(), 1, true, "{\"type\":\"manual\"}", 4, 2,
                    Duration.ofMinutes(5), FlowRepository.Identity.deployer("user-1"), "user-1", null, null));
        }

        assertThat(repository.deployedFlows()).extracting(FlowRepository.DeployedFlow::externalId)
                .contains("board_deployed", "board_elsewhere").doesNotContain("board_never_deployed");
        assertThat(repository.deployedFlow(deployed.id()).orElseThrow().maxConcurrentRuns()).isEqualTo(4);
        assertThat(repository.deployedFlow(deployed.id() + 10_000)).isEmpty();
        assertThat(repository.deployedBoard(new DataSets(false, Set.of(700L)), null, 10))
                .extracting(FlowRepository.BoardFlow::externalId).containsExactly("board_deployed");
        assertThat(repository.deployedBoard(DataSets.ALL, "board_deployed", 10))
                .extracting(FlowRepository.BoardFlow::externalId).contains("board_elsewhere")
                .doesNotContain("board_deployed");
        assertThat(repository.deployedBoard(new DataSets(false, Set.of()), null, 10)).isEmpty();
    }

    private static StoredFlow create(String externalId, long dataSetId) {
        return transaction.execute(s -> repository.create(externalId, dataSetId, "ds_" + dataSetId,
                "Flow " + externalId, definition(externalId), "user-1"));
    }

    private static String definition(String externalId) {
        return TestFlows.EXAMPLE.replace("\"externalId\": \"temp_hourly\"", "\"externalId\": \"" + externalId + "\"");
    }
}
