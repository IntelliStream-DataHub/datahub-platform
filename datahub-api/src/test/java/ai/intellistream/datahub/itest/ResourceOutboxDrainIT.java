// SPDX-License-Identifier: AGPL-3.0-or-later
// Deliberately outside ai.intellistream.datahub.api: the application component-scans that package,
// so a nested @SpringBootConfiguration there would be picked up by every full-context test.
package ai.intellistream.datahub.itest;

import ai.intellistream.datahub.api.messaging.outbox.ResourceOutboxDrainService;
import ai.intellistream.datahub.jpa.domains.Label;
import ai.intellistream.datahub.jpa.domains.ResourceOutboxEntity;
import ai.intellistream.datahub.repositories.label.LabelRepository;
import ai.intellistream.datahub.repositories.outbox.ResourceOutboxRepository;
import ai.intellistream.datahub.services.graph.GraphSyncCommand;
import ai.intellistream.datahub.services.graph.GraphSyncCommandCodec;
import ai.intellistream.datahub.services.graph.Neo4jSchemaInitializer;
import ai.intellistream.datahub.services.graph.ResourceGraphApplier;
import ai.intellistream.datahub.testsupport.SharedPostgres;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * The drain against a real Postgres, with the graph stubbed out — this is about the queue's
 * behaviour, not Cypher.
 *
 * <p>The table comes from the real {@code V42} migration through {@link SharedPostgres}'s
 * template database, so the partial index and the column defaults under test are the ones
 * production gets.
 */
@Tag("integration")
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ContextConfiguration(classes = ResourceOutboxDrainIT.JpaConfig.class)
// The drain manages its own transactions; a test-managed one would swallow them and make the
// advisory-lock assertions meaningless.
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ResourceOutboxDrainIT {

    private static final String TENANT = "acme";

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        String url = SharedPostgres.newDatabase("resource_outbox_drain_it");
        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.username", SharedPostgres::username);
        registry.add("spring.datasource.password", SharedPostgres::password);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        registry.add("spring.jpa.properties.hibernate.dialect", () -> "org.hibernate.dialect.PostgreSQLDialect");
    }

    @SpringBootConfiguration
    @EnableJpaRepositories(basePackageClasses = {LabelRepository.class, ResourceOutboxRepository.class})
    @EntityScan(basePackageClasses = Label.class)
    @EnableTransactionManagement
    static class JpaConfig {
    }

    @Autowired
    private ResourceOutboxRepository repository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    /** Records what the drain handed to the graph, and fails on demand. */
    private static final class RecordingApplier extends ResourceGraphApplier {
        private final List<GraphSyncCommand> applied = new ArrayList<>();
        private long failOnNodeId = -1;
        private CountDownLatch pauseUntil;
        private Runnable onApply;

        RecordingApplier() {
            super(null, null, null);
        }

        @Override
        public void apply(GraphSyncCommand command, String tenantId) {
            if (onApply != null) {
                onApply.run();
            }
            if (pauseUntil != null) {
                try {
                    pauseUntil.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            if (command.upsertNodeIds().contains(failOnNodeId)) {
                throw new IllegalStateException("graph unavailable");
            }
            applied.add(command);
        }
    }

    private RecordingApplier applier;
    private ResourceOutboxDrainService drainService;

    @BeforeEach
    void setUp() {
        repository.deleteAll();
        applier = new RecordingApplier();
        drainService = newDrainService(applier);
    }

    private ResourceOutboxDrainService newDrainService(ResourceGraphApplier applier) {
        return new ResourceOutboxDrainService(repository, applier, mock(Neo4jSchemaInitializer.class),
                transactionManager, 1, Duration.ofSeconds(5), Duration.ofMinutes(10));
    }

    @Test
    void appliesPendingRowsInIdOrderAndStampsThem() {
        queue(1L);
        queue(2L);
        queue(3L);

        drainService.drainOnce(TENANT);

        assertThat(applier.applied.stream().map(c -> c.upsertNodeIds().get(0)))
                .containsExactly(1L, 2L, 3L);
        assertThat(repository.findByAppliedAtIsNullOrderByIdAsc(Limit.of(10)))
                .isEmpty();
    }

    @Test
    void aFailureStopsTheQueueWhereItIsRatherThanSkippingPast() {
        // Order is the whole point: applying row 3 while row 2 is unapplied would converge the
        // graph to a state Postgres never passed through.
        Long first = queue(1L);
        Long failing = queue(2L);
        Long behind = queue(3L);
        applier.failOnNodeId = 2L;

        drainService.drainOnce(TENANT);

        assertThat(applier.applied).hasSize(1);
        assertThat(repository.findById(first).orElseThrow().getAppliedAt()).isNotNull();
        assertThat(repository.findById(behind).orElseThrow().getAppliedAt()).isNull();

        ResourceOutboxEntity failed = repository.findById(failing).orElseThrow();
        assertThat(failed.getAppliedAt()).isNull();
        assertThat(failed.getAttempts()).isEqualTo(1);
        assertThat(failed.getLastError()).contains("graph unavailable");
        assertThat(failed.getNextAttemptAt()).isAfter(Instant.now());
    }

    @Test
    void aRowWaitingOutItsBackoffHoldsEverythingBehindIt() {
        Long blocked = queue(1L);
        queue(2L);
        markFailed(blocked, Instant.now().plus(5, ChronoUnit.MINUTES), "still down");

        drainService.drainOnce(TENANT);

        assertThat(applier.applied).isEmpty();
    }

    @Test
    void aRowWhoseBackoffHasElapsedDrainsWithTheRestOfTheQueue() {
        Long retried = queue(1L);
        queue(2L);
        markFailed(retried, Instant.now().minus(1, ChronoUnit.MINUTES), "was down");

        drainService.drainOnce(TENANT);

        assertThat(applier.applied.stream().map(c -> c.upsertNodeIds().get(0))).containsExactly(1L, 2L);
    }

    @Test
    void onlyOneDrainerAtATimeTouchesATenantsQueue() throws Exception {
        // This is what replaces the single-active Pulsar consumer. The lock lives in the tenant's
        // database, so it arbitrates between api instances, not just threads — two drains here
        // stand in for two pods.
        queue(1L);
        CountDownLatch holdInsideTheLock = new CountDownLatch(1);
        RecordingApplier slowApplier = new RecordingApplier();
        slowApplier.pauseUntil = holdInsideTheLock;
        ResourceOutboxDrainService holder = newDrainService(slowApplier);

        CompletableFuture<Boolean> first = CompletableFuture.supplyAsync(() -> holder.drainOnce(TENANT));
        // Give the first drain time to take the lock before the second one tries.
        Thread.sleep(500);
        boolean secondGotWork = drainService.drainOnce(TENANT);
        holdInsideTheLock.countDown();
        first.get(15, TimeUnit.SECONDS);

        assertThat(secondGotWork).isFalse();
        assertThat(applier.applied).isEmpty();
        assertThat(slowApplier.applied).hasSize(1);
    }

    @Test
    void workQueuedWhileADrainIsRunningIsPickedUpByThatDrain() {
        // requestDrain coalesces: a caller who finds a drain already in flight raises a flag rather
        // than starting a second one, and the running drain has to come back for it. Without that,
        // a write landing mid-drain would wait for the sweep instead of a few milliseconds.
        //
        // The narrower race — the flag raised after the running drain's last check but before it
        // releases — is handled in drainLoop's finally block. It is not pinned here: hitting that
        // window deterministically needs a hook in production code, which is a worse trade than
        // the bounded delay the sweep already covers.
        queue(1L);
        CountDownLatch insideTheDrain = new CountDownLatch(1);
        CountDownLatch releaseTheDrain = new CountDownLatch(1);
        RecordingApplier slowApplier = new RecordingApplier();
        slowApplier.onApply = () -> {
            insideTheDrain.countDown();
            await(releaseTheDrain);
        };
        ResourceOutboxDrainService service = newDrainService(slowApplier);

        service.requestDrain(TENANT);
        await(insideTheDrain);
        // Arrives while the first drain is still running, so it can only set the flag.
        Long queuedDuringDrain = queue(2L);
        service.requestDrain(TENANT);
        releaseTheDrain.countDown();

        awaitApplied(queuedDuringDrain);
        assertThat(repository.findById(queuedDuringDrain).orElseThrow().getAppliedAt()).isNotNull();
    }

    @Test
    void anEmptyQueueIsCheapAndReportsNothingToDo() {
        assertThat(drainService.drainOnce(TENANT)).isFalse();
        assertThat(repository.existsByAppliedAtIsNull()).isFalse();
    }

    /**
     * Puts a row into the state a failed drain would leave it in. It needs its own transaction
     * here because the production caller already has one — the failure stamp has to commit with
     * the applied-markers of the rows that succeeded before it.
     */
    private void markFailed(Long rowId, Instant nextAttemptAt, String error) {
        new TransactionTemplate(transactionManager).executeWithoutResult(
                status -> repository.recordFailure(rowId, 1, nextAttemptAt, error));
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new AssertionError("timed out waiting for the drain");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    /** The drain is asynchronous, so poll rather than assuming it has finished. */
    private void awaitApplied(Long rowId) {
        for (int i = 0; i < 100; i++) {
            if (repository.findById(rowId).map(r -> r.getAppliedAt() != null).orElse(false)) {
                return;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        }
        throw new AssertionError("row " + rowId + " was never applied");
    }

    private Long queue(Long nodeId) {
        GraphSyncCommand command =
                new GraphSyncCommand(List.of(nodeId), List.of(), List.of(), List.of());
        return repository.save(new ResourceOutboxEntity(GraphSyncCommandCodec.toJson(command))).getId();
    }
}
